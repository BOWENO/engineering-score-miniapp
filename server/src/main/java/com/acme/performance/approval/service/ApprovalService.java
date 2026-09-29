package com.acme.performance.approval.service;

import com.acme.performance.approval.model.ApprovalView;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.service.DataScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ApprovalService {
    private static final Set<String> APPROVER_ROLES = Set.of("ASSISTANT_ENGINEER", "SUPERVISOR");
    private final JdbcClient jdbc;
    private final DataScopeService dataScope;
    private final ObjectMapper objectMapper;

    public ApprovalService(JdbcClient jdbc, DataScopeService dataScope, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.dataScope = dataScope;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> pending(CurrentUser actor) {
        requireApprover(actor);
        return jdbc.sql("""
                SELECT p.id AS approval_id,p.version,p.status,a.id AS application_id,a.applicant_id,
                       u.display_name,u.employee_no,a.rule_code,r.title,r.score,a.description,a.occurred_at
                FROM approval_instance p JOIN score_application a ON a.id=p.biz_id AND p.biz_type='SCORE_APPLICATION'
                JOIN app_user u ON u.id=a.applicant_id
                JOIN score_rule r ON r.rule_version_id=a.rule_version_id AND r.code=a.rule_code
                WHERE p.status='PENDING' ORDER BY a.submitted_at
                """).query((rs, n) -> new ApprovalView(rs.getObject("approval_id", UUID.class), rs.getLong("version"),
                        rs.getObject("application_id", UUID.class), rs.getObject("applicant_id", UUID.class),
                        rs.getString("display_name"), rs.getString("employee_no"), rs.getString("rule_code"),
                        rs.getString("title"), rs.getBigDecimal("score"), rs.getString("description"),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("status"),
                        attachments(rs.getObject("application_id", UUID.class)))).list().stream()
                .filter(item -> dataScope.canAccessUser(actor, item.applicantId())).toList();
    }

    @Transactional
    public ActionResult act(CurrentUser actor, UUID approvalId, long version, String action,
                            String reasonCode, String comment, String requestId) {
        requireApprover(actor);
        String normalized = action == null ? "" : action.toUpperCase();
        if (!Set.of("APPROVE", "REJECT", "RETURN").contains(normalized)) {
            throw new ApiException("INVALID_APPROVAL_ACTION", "不支持的审核动作", HttpStatus.BAD_REQUEST);
        }
        if (!"APPROVE".equals(normalized) && (reasonCode == null || reasonCode.isBlank())) {
            throw new ApiException("REASON_REQUIRED", "驳回或退回必须选择原因", HttpStatus.BAD_REQUEST);
        }
        ApprovalRow row = load(approvalId);
        if (!dataScope.canAccessUser(actor, row.applicantId())) throw new ApiException("FORBIDDEN", "无权审核该申报", HttpStatus.FORBIDDEN);
        if (!"PENDING".equals(row.approvalStatus())) throw new ApiException("APPROVAL_ALREADY_DECIDED", "审核已处理", HttpStatus.CONFLICT);
        if (row.approvalVersion() != version) throw new ApiException("VERSION_CONFLICT", "审核已被其他操作更新", HttpStatus.CONFLICT);

        String target = switch (normalized) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; default -> "RETURNED"; };
        int updated = jdbc.sql("UPDATE approval_instance SET status=:status,version=version+1 WHERE id=:id AND version=:version AND status='PENDING'")
                .param("status", target).param("id", approvalId).param("version", version).update();
        if (updated != 1) throw new ApiException("VERSION_CONFLICT", "审核已被其他操作更新", HttpStatus.CONFLICT);
        jdbc.sql("""
                UPDATE score_application SET status=:status,decision_reason_code=:reason,decision_comment=:comment,
                       decided_at=CASE WHEN :status IN ('APPROVED','REJECTED') THEN CURRENT_TIMESTAMP ELSE NULL END,version=version+1
                WHERE id=:id
                """).param("status", target).param("reason", reasonCode).param("comment", comment).param("id", row.applicationId()).update();
        jdbc.sql("""
                INSERT INTO approval_action(id,approval_id,actor_id,action,reason_code,comment,from_status,to_status)
                VALUES (:id,:approvalId,:actorId,:action,:reason,:comment,'PENDING',:target)
                """).param("id", UUID.randomUUID()).param("approvalId", approvalId).param("actorId", actor.userId())
                .param("action", normalized).param("reason", reasonCode).param("comment", comment).param("target", target).update();
        UUID eventId = "APPROVE".equals(normalized) ? postScore(row) : null;
        jdbc.sql("""
                INSERT INTO operation_log(id,actor_id,action,target_type,target_id,after_data,request_id)
                VALUES (:id,:actorId,:action,'APPROVAL',:targetId,CAST(:afterData AS jsonb),:requestId)
                """).param("id", UUID.randomUUID()).param("actorId", actor.userId()).param("action", "APPROVAL_" + normalized)
                .param("targetId", approvalId).param("afterData", "{\"status\":\"" + target + "\"}").param("requestId", requestId).update();
        return new ActionResult(approvalId, target, version + 1, eventId);
    }

    private UUID postScore(ApprovalRow row) {
        String period = YearMonth.from(row.occurredAt().atZoneSameInstant(ZoneId.of("Asia/Shanghai"))).toString();
        BigDecimal original = row.score();
        BigDecimal actual = original;
        String capReason = null;
        try {
            JsonNode policy = objectMapper.readTree(row.capPolicy());
            if (policy.has("monthlyCap")) {
                BigDecimal cap = policy.get("monthlyCap").decimalValue();
                BigDecimal used = jdbc.sql("SELECT COALESCE(bonus,0) FROM score_summary WHERE user_id=:userId AND period=:period")
                        .param("userId", row.applicantId()).param("period", period).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
                BigDecimal remaining = cap.subtract(used).max(BigDecimal.ZERO);
                if (actual.compareTo(remaining) > 0) { actual = remaining; capReason = "MONTHLY_CAP"; }
            }
        } catch (Exception ex) {
            throw new ApiException("INVALID_RULE_CONFIGURATION", "规则封顶配置无法解析", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,cap_reason,source,source_id)
                VALUES (:id,:userId,:ruleCode,:bizDate,:original,:actual,:capReason,'APPLICATION',:sourceId)
                ON CONFLICT (source,source_id,rule_code) DO NOTHING
                """).param("id", eventId).param("userId", row.applicantId()).param("ruleCode", row.ruleCode())
                .param("bizDate", row.occurredAt().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate())
                .param("original", original).param("actual", actual).param("capReason", capReason)
                .param("sourceId", row.applicationId()).update();
        jdbc.sql("""
                INSERT INTO score_summary(user_id,period,base,bonus,penalty,total)
                VALUES (:userId,:period,0,:actual,0,:actual)
                ON CONFLICT (user_id,period) DO UPDATE SET bonus=score_summary.bonus+EXCLUDED.bonus,
                  total=score_summary.total+EXCLUDED.total,rebuilt_at=CURRENT_TIMESTAMP
                """).param("userId", row.applicantId()).param("period", period).param("actual", actual).update();
        return eventId;
    }

    private ApprovalRow load(UUID id) {
        return jdbc.sql("""
                SELECT p.id,p.version AS approval_version,p.status AS approval_status,a.id AS application_id,a.applicant_id,
                       a.rule_code,a.occurred_at,r.score,r.cap_policy::text
                FROM approval_instance p JOIN score_application a ON a.id=p.biz_id
                JOIN score_rule r ON r.rule_version_id=a.rule_version_id AND r.code=a.rule_code
                WHERE p.id=:id AND p.biz_type='SCORE_APPLICATION'
                """).param("id", id).query((rs, n) -> new ApprovalRow(rs.getObject("application_id", UUID.class),
                        rs.getObject("applicant_id", UUID.class), rs.getString("rule_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getBigDecimal("score"),
                        rs.getString("cap_policy"), rs.getString("approval_status"), rs.getLong("approval_version")))
                .optional().orElseThrow(() -> new ApiException("APPROVAL_NOT_FOUND", "审核任务不存在", HttpStatus.NOT_FOUND));
    }

    private List<ApprovalView.AttachmentItem> attachments(UUID applicationId) {
        return jdbc.sql("""
                SELECT f.id,f.mime_type,f.size_bytes,f.content_hash FROM attachment f
                JOIN application_attachment x ON x.attachment_id=f.id WHERE x.application_id=:id
                """).param("id", applicationId).query((rs, n) -> new ApprovalView.AttachmentItem(
                        rs.getObject("id", UUID.class), rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("content_hash"))).list();
    }

    private void requireApprover(CurrentUser user) {
        if (user.roles().stream().noneMatch(APPROVER_ROLES::contains)) {
            throw new ApiException("FORBIDDEN", "当前角色不能执行业务审核", HttpStatus.FORBIDDEN);
        }
    }

    public record ActionResult(UUID approvalId, String status, long version, UUID scoreEventId) {}
    private record ApprovalRow(UUID applicationId, UUID applicantId, String ruleCode, OffsetDateTime occurredAt,
                               BigDecimal score, String capPolicy, String approvalStatus, long approvalVersion) {}
}
