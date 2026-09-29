package com.acme.performance.application.service;

import com.acme.performance.common.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ScoreApplicationService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ScoreApplicationService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ApplicationResult create(UUID applicantId, String idempotencyKey, String ruleCode,
                                    OffsetDateTime occurredAt, String description, List<UUID> attachmentIds,
                                    boolean submit) {
        requireIdempotencyKey(idempotencyKey);
        var existing = jdbc.sql("SELECT id,status,version FROM score_application WHERE applicant_id=:userId AND idempotency_key=:key")
                .param("userId", applicantId).param("key", idempotencyKey)
                .query((rs, n) -> new ApplicationResult(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version"), true))
                .optional();
        if (existing.isPresent()) return existing.get();
        RuleRow rule = activeBonusRule(ruleCode);
        List<UUID> attachments = attachmentIds == null ? List.of() : attachmentIds.stream().distinct().toList();
        validateAttachments(applicantId, attachments);
        if (submit) validateSubmission(description, attachments, rule.evidenceSchema());
        UUID id = UUID.randomUUID();
        String status = submit ? "SUBMITTED" : "DRAFT";
        jdbc.sql("""
                INSERT INTO score_application(id,applicant_id,rule_code,rule_version_id,occurred_at,description,status,idempotency_key,submitted_at)
                VALUES (:id,:applicantId,:ruleCode,:ruleVersionId,:occurredAt,:description,:status,:key,
                        CASE WHEN :submitted THEN CURRENT_TIMESTAMP ELSE NULL END)
                """).param("id", id).param("applicantId", applicantId).param("ruleCode", ruleCode)
                .param("ruleVersionId", rule.ruleVersionId()).param("occurredAt", occurredAt == null ? OffsetDateTime.now() : occurredAt)
                .param("description", description == null ? "" : description).param("status", status)
                .param("key", idempotencyKey).param("submitted", submit).update();
        linkAttachments(id, attachments);
        if (submit) createApproval(id);
        return new ApplicationResult(id, status, 0, false);
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> mine(UUID applicantId) {
        return jdbc.sql("""
                SELECT a.id,a.rule_code,COALESCE(r.title,a.rule_code) AS title,a.occurred_at,
                       a.description,a.status,a.decision_reason_code,a.decision_comment,a.version,a.created_at
                FROM score_application a
                LEFT JOIN score_rule r ON r.rule_version_id=a.rule_version_id AND r.code=a.rule_code
                WHERE a.applicant_id=:applicantId
                ORDER BY a.created_at DESC
                LIMIT 100
                """)
                .param("applicantId", applicantId)
                .query((rs, n) -> new ApplicationView(
                        rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("title"),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("description"),
                        rs.getString("status"), rs.getString("decision_reason_code"),
                        rs.getString("decision_comment"), rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public ApplicationResult submitDraft(UUID applicantId, UUID applicationId, long version) {
        DraftRow draft = jdbc.sql("""
                SELECT a.id,a.description,a.status,a.version,r.evidence_schema::text
                FROM score_application a JOIN score_rule r ON r.rule_version_id=a.rule_version_id AND r.code=a.rule_code
                WHERE a.id=:id AND a.applicant_id=:applicantId
                """).param("id", applicationId).param("applicantId", applicantId)
                .query((rs, n) -> new DraftRow(rs.getString("description"), rs.getString("status"), rs.getLong("version"), rs.getString("evidence_schema")))
                .optional().orElseThrow(() -> new ApiException("APPLICATION_NOT_FOUND", "申报不存在", HttpStatus.NOT_FOUND));
        if (!"DRAFT".equals(draft.status()) && !"RETURNED".equals(draft.status())) {
            throw new ApiException("INVALID_APPLICATION_STATUS", "当前状态不能提交", HttpStatus.CONFLICT);
        }
        if (draft.version() != version) throw new ApiException("VERSION_CONFLICT", "申报已被其他操作更新", HttpStatus.CONFLICT);
        List<UUID> attachmentIds = jdbc.sql("SELECT attachment_id FROM application_attachment WHERE application_id=:id")
                .param("id", applicationId).query(UUID.class).list();
        validateSubmission(draft.description(), attachmentIds, draft.evidenceSchema());
        int updated = jdbc.sql("""
                UPDATE score_application SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=:id AND version=:version AND status IN ('DRAFT','RETURNED')
                """).param("id", applicationId).param("version", version).update();
        if (updated != 1) throw new ApiException("VERSION_CONFLICT", "申报已被其他操作更新", HttpStatus.CONFLICT);
        createApproval(applicationId);
        return new ApplicationResult(applicationId, "SUBMITTED", version + 1, false);
    }

    private RuleRow activeBonusRule(String code) {
        return jdbc.sql("""
                SELECT r.rule_version_id,r.evidence_schema::text FROM score_rule r JOIN rule_version rv ON rv.id=r.rule_version_id
                WHERE r.code=:code AND r.type='BONUS' AND rv.status='PUBLISHED' AND rv.effective_at<=CURRENT_TIMESTAMP AND r.effective_from<=CURRENT_DATE
                  AND (r.effective_to IS NULL OR r.effective_to>=CURRENT_DATE)
                """).param("code", code).query((rs, n) -> new RuleRow(rs.getObject("rule_version_id", UUID.class), rs.getString("evidence_schema")))
                .optional().orElseThrow(() -> new ApiException("RULE_NOT_AVAILABLE", "加分规则不存在或未生效", HttpStatus.BAD_REQUEST));
    }

    private void validateAttachments(UUID ownerId, List<UUID> ids) {
        if (ids.isEmpty()) return;
        Long count = jdbc.sql("SELECT COUNT(*) FROM attachment WHERE owner_id=:ownerId AND id IN (:ids)")
                .param("ownerId", ownerId).param("ids", ids).query(Long.class).single();
        if (count != ids.size()) throw new ApiException("INVALID_ATTACHMENT", "附件不存在或不属于当前用户", HttpStatus.BAD_REQUEST);
    }

    private void validateSubmission(String description, List<UUID> attachments, String schemaText) {
        if (description == null || description.isBlank()) {
            throw new ApiException("DESCRIPTION_REQUIRED", "提交申报必须填写说明", HttpStatus.BAD_REQUEST);
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaText);
            boolean attachmentRequired = schema.path("attachmentRequired").asBoolean(false);
            if (attachmentRequired && attachments.isEmpty()) {
                throw new ApiException("ATTACHMENT_REQUIRED", "该规则必须上传凭证", HttpStatus.BAD_REQUEST);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ApiException("INVALID_RULE_CONFIGURATION", "规则凭证配置无法解析", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void linkAttachments(UUID applicationId, List<UUID> ids) {
        ids.forEach(id -> jdbc.sql("INSERT INTO application_attachment(application_id,attachment_id) VALUES (:applicationId,:attachmentId)")
                .param("applicationId", applicationId).param("attachmentId", id).update());
    }

    private void createApproval(UUID applicationId) {
        jdbc.sql("INSERT INTO approval_instance(id,biz_type,biz_id,current_node,status) VALUES (:id,'SCORE_APPLICATION',:bizId,'SUPERVISOR','PENDING')")
                .param("id", UUID.randomUUID()).param("bizId", applicationId).update();
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException("IDEMPOTENCY_KEY_REQUIRED", "必须提供有效的Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
    }

    public record ApplicationResult(UUID id, String status, long version, boolean replayed) {}
    public record ApplicationView(UUID id, String ruleCode, String title, OffsetDateTime occurredAt,
                                  String description, String status, String decisionReasonCode,
                                  String decisionComment, long version, OffsetDateTime createdAt) {}
    private record RuleRow(UUID ruleVersionId, String evidenceSchema) {}
    private record DraftRow(String description, String status, long version, String evidenceSchema) {}
}
