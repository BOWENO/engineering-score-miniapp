package com.acme.performance.appeal.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.service.DataScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AppealService {
    private final JdbcClient jdbc;
    private final DataScopeService dataScope;
    private final AuditLogService audit;
    public AppealService(JdbcClient jdbc, DataScopeService dataScope, AuditLogService audit) {
        this.jdbc = jdbc; this.dataScope = dataScope; this.audit = audit;
    }

    @Transactional
    public UUID submit(CurrentUser user, UUID resultId, String reasonCode, String description) {
        Long owned = jdbc.sql("SELECT COUNT(*) FROM grade_snapshot WHERE id=:id AND user_id=:userId AND status='PUBLISHED'")
                .param("id", resultId).param("userId", user.userId()).query(Long.class).single();
        if (owned != 1) throw new ApiException("RESULT_NOT_FOUND", "已发布结果不存在", HttpStatus.NOT_FOUND);
        if (reasonCode == null || reasonCode.isBlank() || description == null || description.isBlank()) {
            throw new ApiException("APPEAL_REASON_REQUIRED", "申诉原因和说明不能为空", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO appeal(id,result_id,applicant_id,reason_code,description,status) VALUES (:id,:resultId,:userId,:reason,:description,'PENDING')")
                    .param("id", id).param("resultId", resultId).param("userId", user.userId())
                    .param("reason", reasonCode).param("description", description).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException("APPEAL_EXISTS", "该结果已提交申诉", HttpStatus.CONFLICT);
        }
        return id;
    }

    public List<AppealView> mine(CurrentUser user) { return query("WHERE a.applicant_id=:userId", user.userId()); }

    public List<AppealView> pending(CurrentUser actor) {
        requireManager(actor);
        return query("WHERE a.status='PENDING'", null).stream().filter(a -> dataScope.canAccessUser(actor, a.applicantId())).toList();
    }

    @Transactional
    public void decide(CurrentUser actor, UUID appealId, String decision, String comment, String requestId) {
        requireManager(actor);
        String normalized = decision == null ? "" : decision.toUpperCase();
        if (!Set.of("ACCEPT", "REJECT").contains(normalized) || comment == null || comment.isBlank()) {
            throw new ApiException("INVALID_APPEAL_DECISION", "复核结论和说明不能为空", HttpStatus.BAD_REQUEST);
        }
        UUID applicant = jdbc.sql("SELECT applicant_id FROM appeal WHERE id=:id AND status='PENDING'").param("id", appealId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException("APPEAL_NOT_FOUND", "待处理申诉不存在", HttpStatus.NOT_FOUND));
        if (!dataScope.canAccessUser(actor, applicant)) throw new ApiException("FORBIDDEN", "无权处理该申诉", HttpStatus.FORBIDDEN);
        jdbc.sql("UPDATE appeal SET status='DECIDED',decision=:decision,decision_comment=:comment,decided_by=:actorId,decided_at=CURRENT_TIMESTAMP WHERE id=:id AND status='PENDING'")
                .param("decision", normalized).param("comment", comment).param("actorId", actor.userId()).param("id", appealId).update();
        audit.record(actor.userId(), "APPEAL_DECIDE", "APPEAL", appealId, "{\"decision\":\"" + normalized + "\"}", requestId);
    }

    private List<AppealView> query(String where, UUID userId) {
        var spec = jdbc.sql("""
                SELECT a.id,a.result_id,a.applicant_id,u.display_name,a.reason_code,a.description,a.status,a.decision,a.decision_comment,a.created_at
                FROM appeal a JOIN app_user u ON u.id=a.applicant_id
                """ + where + " ORDER BY a.created_at DESC");
        if (userId != null) spec.param("userId", userId);
        return spec.query((rs, n) -> new AppealView(rs.getObject("id", UUID.class), rs.getObject("result_id", UUID.class),
                rs.getObject("applicant_id", UUID.class), rs.getString("display_name"), rs.getString("reason_code"),
                rs.getString("description"), rs.getString("status"), rs.getString("decision"),
                rs.getString("decision_comment"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }
    private void requireManager(CurrentUser actor) {
        if (!actor.roles().contains("DEPARTMENT_MANAGER")) throw new ApiException("FORBIDDEN", "仅部门经理可处理申诉", HttpStatus.FORBIDDEN);
    }
    public record AppealView(UUID id, UUID resultId, UUID applicantId, String applicantName, String reasonCode,
                             String description, String status, String decision, String decisionComment, OffsetDateTime createdAt) {}
}
