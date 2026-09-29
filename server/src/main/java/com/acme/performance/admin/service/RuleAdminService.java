package com.acme.performance.admin.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RuleAdminService {
    private static final Set<String> TYPES = Set.of("BASE", "BONUS", "PENALTY", "D_GRADE", "REVERSAL");
    private final JdbcClient jdbc;
    private final AdminGuard guard;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public RuleAdminService(JdbcClient jdbc, AdminGuard guard, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc; this.guard = guard; this.audit = audit; this.mapper = mapper;
    }

    public List<VersionView> versions(CurrentUser user) {
        if (!user.administrator() && !user.roles().contains("DEPARTMENT_MANAGER")) {
            throw new ApiException("FORBIDDEN", "无权查看规则版本", HttpStatus.FORBIDDEN);
        }
        return jdbc.sql("""
                SELECT id,version,status,effective_at,business_approved_at,published_at,created_at
                FROM rule_version ORDER BY created_at DESC
                """).query((rs, n) -> new VersionView(rs.getObject("id", UUID.class), rs.getString("version"),
                        rs.getString("status"), rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getObject("business_approved_at", OffsetDateTime.class), rs.getObject("published_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public List<RuleView> rules(CurrentUser user, UUID versionId) {
        if (!user.administrator() && !user.roles().contains("DEPARTMENT_MANAGER"))
            throw new ApiException("FORBIDDEN", "无权查看积分规则", HttpStatus.FORBIDDEN);
        versionStatus(versionId);
        return jdbc.sql("""
                SELECT id,code,title,type,score,cap_policy::text,evidence_schema::text,approval_flow::text,effective_from,effective_to
                FROM score_rule WHERE rule_version_id=:versionId ORDER BY code
                """).param("versionId", versionId).query((rs, n) -> new RuleView(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("title"), rs.getString("type"),
                rs.getBigDecimal("score"), parseJson(rs.getString("cap_policy")), parseJson(rs.getString("evidence_schema")),
                parseJson(rs.getString("approval_flow")), rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class))).list();
    }

    @Transactional
    public VersionView createVersion(CurrentUser actor, String version, String requestId) {
        guard.requireSystemAdmin(actor);
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new ApiException("INVALID_RULE_VERSION", "规则版本号不合法", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO rule_version(id,version,status,created_by) VALUES (:id,:version,'DRAFT',:actorId)")
                    .param("id", id).param("version", version).param("actorId", actor.userId()).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException("RULE_VERSION_EXISTS", "规则版本号已存在", HttpStatus.CONFLICT);
        }
        audit.record(actor.userId(), "RULE_VERSION_CREATE", "RULE_VERSION", id, json(java.util.Map.of("version", version)), requestId);
        return new VersionView(id, version, "DRAFT", null, null, null, OffsetDateTime.now());
    }

    @Transactional
    public UUID addRule(CurrentUser actor, UUID versionId, RuleInput input, String requestId) {
        guard.requireSystemAdmin(actor);
        String status = versionStatus(versionId);
        if (!"DRAFT".equals(status)) throw new ApiException("RULE_VERSION_LOCKED", "只有草稿版本可以编辑", HttpStatus.CONFLICT);
        String type = input.type() == null ? "" : input.type().toUpperCase();
        if (!TYPES.contains(type) || input.code() == null || !input.code().matches("[A-Z][A-Z0-9_-]{1,31}") || input.title() == null || input.title().isBlank()) {
            throw new ApiException("INVALID_RULE", "规则编码、类型或名称不合法", HttpStatus.BAD_REQUEST);
        }
        if (!"D_GRADE".equals(type) && input.score() == null) throw new ApiException("RULE_SCORE_REQUIRED", "该规则必须填写分值", HttpStatus.BAD_REQUEST);
        String cap = validJson(input.capPolicy());
        String evidence = validJson(input.evidenceSchema());
        String flow = validJson(input.approvalFlow());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO score_rule(id,rule_version_id,code,title,type,score,cap_policy,evidence_schema,approval_flow,effective_from,effective_to)
                    VALUES (:id,:versionId,:code,:title,:type,:score,CAST(:cap AS jsonb),CAST(:evidence AS jsonb),CAST(:flow AS jsonb),:from,:to)
                    """).param("id", id).param("versionId", versionId).param("code", input.code())
                    .param("title", input.title().trim()).param("type", type).param("score", input.score())
                    .param("cap", cap).param("evidence", evidence).param("flow", flow)
                    .param("from", input.effectiveFrom()).param("to", input.effectiveTo()).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException("RULE_CODE_EXISTS", "该版本中规则编码已存在", HttpStatus.CONFLICT);
        }
        audit.record(actor.userId(), "RULE_CREATE", "SCORE_RULE", id, json(java.util.Map.of("code", input.code(), "type", type)), requestId);
        return id;
    }

    @Transactional
    public void deleteRule(CurrentUser actor, UUID versionId, UUID ruleId, String requestId) {
        guard.requireSystemAdmin(actor);
        if (!"DRAFT".equals(versionStatus(versionId)))
            throw new ApiException("RULE_VERSION_LOCKED", "只有草稿版本可以编辑", HttpStatus.CONFLICT);
        int deleted = jdbc.sql("DELETE FROM score_rule WHERE id=:ruleId AND rule_version_id=:versionId")
                .param("ruleId", ruleId).param("versionId", versionId).update();
        if (deleted != 1) throw new ApiException("RULE_NOT_FOUND", "规则不存在", HttpStatus.NOT_FOUND);
        audit.record(actor.userId(), "RULE_DELETE", "SCORE_RULE", ruleId, "{}", requestId);
    }

    @Transactional
    public void businessApprove(CurrentUser actor, UUID versionId, String requestId) {
        guard.requireSystemAdmin(actor);
        if (!"DRAFT".equals(versionStatus(versionId))) throw new ApiException("INVALID_RULE_VERSION_STATUS", "仅草稿版本可提交业务审批", HttpStatus.CONFLICT);
        Long rules = jdbc.sql("SELECT COUNT(*) FROM score_rule WHERE rule_version_id=:id").param("id", versionId).query(Long.class).single();
        if (rules == 0) throw new ApiException("EMPTY_RULE_VERSION", "空规则版本不能审批", HttpStatus.BAD_REQUEST);
        jdbc.sql("UPDATE rule_version SET status='BUSINESS_APPROVED',business_approved_by=:actorId,business_approved_at=CURRENT_TIMESTAMP WHERE id=:id AND status='DRAFT'")
                .param("actorId", actor.userId()).param("id", versionId).update();
        audit.record(actor.userId(), "RULE_VERSION_BUSINESS_APPROVE", "RULE_VERSION", versionId, "{\"status\":\"BUSINESS_APPROVED\"}", requestId);
    }

    @Transactional
    public void publish(CurrentUser actor, UUID versionId, OffsetDateTime effectiveAt, String requestId) {
        guard.requireSystemAdmin(actor);
        String status = versionStatus(versionId);
        if (!Set.of("DRAFT", "BUSINESS_APPROVED").contains(status))
            throw new ApiException("INVALID_RULE_VERSION_STATUS", "仅草稿或已确认版本可以发布", HttpStatus.CONFLICT);
        Long rules = jdbc.sql("SELECT COUNT(*) FROM score_rule WHERE rule_version_id=:id").param("id", versionId).query(Long.class).single();
        if (rules == 0) throw new ApiException("EMPTY_RULE_VERSION", "空规则版本不能发布", HttpStatus.BAD_REQUEST);
        jdbc.sql("UPDATE rule_version SET status='RETIRED' WHERE status='PUBLISHED' AND id<>:id").param("id", versionId).update();
        int updated = jdbc.sql("""
                UPDATE rule_version SET status='PUBLISHED',effective_at=:effectiveAt,published_by=:actorId,published_at=CURRENT_TIMESTAMP,
                  business_approved_by=COALESCE(business_approved_by,:actorId),business_approved_at=COALESCE(business_approved_at,CURRENT_TIMESTAMP)
                WHERE id=:id AND status IN ('DRAFT','BUSINESS_APPROVED')
                """).param("effectiveAt", effectiveAt == null ? OffsetDateTime.now() : effectiveAt)
                .param("actorId", actor.userId()).param("id", versionId).update();
        if (updated != 1) throw new ApiException("VERSION_CONFLICT", "规则版本状态已变化", HttpStatus.CONFLICT);
        audit.record(actor.userId(), "RULE_VERSION_PUBLISH", "RULE_VERSION", versionId, "{\"status\":\"PUBLISHED\"}", requestId);
    }

    private String versionStatus(UUID id) {
        return jdbc.sql("SELECT status FROM rule_version WHERE id=:id").param("id", id).query(String.class).optional()
                .orElseThrow(() -> new ApiException("RULE_VERSION_NOT_FOUND", "规则版本不存在", HttpStatus.NOT_FOUND));
    }
    private String validJson(JsonNode node) {
        if (node == null || node.isNull()) return "{}";
        if (!node.isObject()) throw new ApiException("INVALID_RULE_JSON", "规则策略必须是JSON对象", HttpStatus.BAD_REQUEST);
        return node.toString();
    }
    private JsonNode parseJson(String value) { try { return mapper.readTree(value); } catch (Exception ex) { return mapper.createObjectNode(); } }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }

    public record VersionView(UUID id, String version, String status, OffsetDateTime effectiveAt,
                              OffsetDateTime businessApprovedAt, OffsetDateTime publishedAt, OffsetDateTime createdAt) {}
    public record RuleInput(String code, String title, String type, BigDecimal score, JsonNode capPolicy,
                            JsonNode evidenceSchema, JsonNode approvalFlow, LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record RuleView(UUID id, String code, String title, String type, BigDecimal score, JsonNode capPolicy,
                           JsonNode evidenceSchema, JsonNode approvalFlow, LocalDate effectiveFrom, LocalDate effectiveTo) {}
}
