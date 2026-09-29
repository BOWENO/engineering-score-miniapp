package com.acme.performance.settlement.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.organization.model.BusinessRoles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SettlementCorrectionService {
    private final SettlementAccess access;
    private final JdbcClient jdbc;
    private final SettlementService settlements;
    private final NotificationService notifications;
    private final AuditLogService audit;

    public SettlementCorrectionService(JdbcClient jdbc, SettlementService settlements,
                                       NotificationService notifications, AuditLogService audit) {
        this.jdbc = jdbc;
        this.access = new SettlementAccess(jdbc);
        this.settlements = settlements;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public CorrectionView request(CurrentUser actor, UUID runId, UUID userId, int adjustmentScore,
                                  String reason, String requestId) {
        requireSupervisor(actor);
        if (adjustmentScore == 0 || adjustmentScore < -100 || adjustmentScore > 100
                || reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new ApiException("INVALID_SETTLEMENT_CORRECTION", "更正分值必须为-100至100的非零整数，并填写原因", HttpStatus.BAD_REQUEST);
        }
        Run run = lockedRun(runId);
        access.requireWrite(actor, run.orgId());
        if (!"PUBLISHED".equals(run.status())) {
            throw new ApiException("SETTLEMENT_NOT_PUBLISHED", "仅已封存的月度绩效可以发起更正", HttpStatus.CONFLICT);
        }
        long candidate = jdbc.sql("SELECT COUNT(*) FROM settlement_candidate WHERE run_id=:runId AND user_id=:userId")
                .param("runId", runId).param("userId", userId).query(Long.class).single();
        if (candidate != 1) throw new ApiException("SETTLEMENT_USER_NOT_FOUND", "该人员不在本次封存中", HttpStatus.NOT_FOUND);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO settlement_correction(id,settlement_run_id,user_id,reason,adjustment_score,requested_by,status)
                    VALUES (:id,:runId,:userId,:reason,:score,:actorId,'PENDING')
                    """).param("id", id).param("runId", runId).param("userId", userId)
                    .param("reason", reason.trim()).param("score", adjustmentScore)
                    .param("actorId", actor.userId()).update();
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("CORRECTION_ALREADY_PENDING", "该人员已有待表决的封存更正", HttpStatus.CONFLICT);
        }
        for(UUID supervisor : access.supervisors(run.orgId())) jdbc.sql("INSERT INTO settlement_correction_approver(correction_id,user_id) VALUES (:id,:user)")
                .param("id",id).param("user",supervisor).update();
        audit.record(actor.userId(), "SETTLEMENT_CORRECTION_REQUEST", "SETTLEMENT_CORRECTION", id,
                "{\"adjustmentScore\":" + adjustmentScore + "}", requestId);
        String targetName = jdbc.sql("SELECT display_name FROM app_user WHERE id=:id").param("id", userId).query(String.class).single();
        approvers(id).forEach(supervisor -> notifications.create(supervisor, "SETTLEMENT_CORRECTION_PENDING",
                "封存更正待表决", run.period() + "月 · " + targetName + " · "
                        + (adjustmentScore > 0 ? "+" : "") + adjustmentScore + "分", "SETTLEMENT_CORRECTION", id, false));
        return get(id);
    }

    @Transactional
    public CorrectionView vote(CurrentUser actor, UUID correctionId, String decision, String comment, String requestId) {
        requireSupervisor(actor);
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!Set.of("APPROVE", "REJECT").contains(normalized)) {
            throw new ApiException("INVALID_CORRECTION_VOTE", "更正表决只能通过或驳回", HttpStatus.BAD_REQUEST);
        }
        CorrectionView existing = get(correctionId);
        Run parent = lockedRun(existing.runId());
        access.requireWrite(actor, parent.orgId());
        if (!approvers(correctionId).contains(actor.userId())) throw new ApiException("FORBIDDEN", "您不在本次更正的审批人名单中", HttpStatus.FORBIDDEN);
        if (!"PUBLISHED".equals(parent.status())) throw new ApiException("SETTLEMENT_ALREADY_CORRECTED", "原封存批次已被更正，请在新批次重新发起", HttpStatus.CONFLICT);
        CorrectionView item = getForUpdate(correctionId);
        if (!"PENDING".equals(item.status())) {
            throw new ApiException("CORRECTION_ALREADY_DECIDED", "该更正已经完成表决", HttpStatus.CONFLICT);
        }
        try {
            jdbc.sql("""
                    INSERT INTO settlement_correction_vote(id,correction_id,supervisor_id,decision,comment)
                    VALUES (:id,:correctionId,:supervisorId,:decision,:comment)
                    """).param("id", UUID.randomUUID()).param("correctionId", correctionId)
                    .param("supervisorId", actor.userId()).param("decision", normalized)
                    .param("comment", clean(comment)).update();
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("CORRECTION_ALREADY_VOTED", "每位主管只能表决一次", HttpStatus.CONFLICT);
        }
        audit.record(actor.userId(), "SETTLEMENT_CORRECTION_VOTE_" + normalized,
                "SETTLEMENT_CORRECTION", correctionId, "{}", requestId);
        if ("REJECT".equals(normalized)) {
            jdbc.sql("UPDATE settlement_correction SET status='REJECTED',decided_at=CURRENT_TIMESTAMP WHERE id=:id")
                    .param("id", correctionId).update();
        } else if (approvalCount(correctionId) >= approvers(correctionId).size()) {
            applyCorrection(actor, item, requestId);
        }
        return get(correctionId);
    }

    @Transactional(readOnly = true)
    public List<CorrectionView> list(CurrentUser actor, String period) {
        requireViewer(actor);
        DGradeService.validatePeriod(period);
        return jdbc.sql("""
                SELECT c.id FROM settlement_correction c JOIN settlement_run r ON r.id=c.settlement_run_id
                WHERE r.period=:period ORDER BY c.created_at DESC
                """).param("period", period).query(UUID.class).list().stream().map(this::get).filter(item -> access.canAccess(actor,run(item.runId()).orgId())).toList();
    }

    private void applyCorrection(CurrentUser actor, CorrectionView item, String requestId) {
        Run run = run(item.runId());
        if (!"PUBLISHED".equals(run.status())) {
            throw new ApiException("SETTLEMENT_ALREADY_CORRECTED", "原封存批次已被更正", HttpStatus.CONFLICT);
        }
        YearMonth month = YearMonth.parse(run.period());
        BigDecimal score = BigDecimal.valueOf(item.adjustmentScore());
        jdbc.sql("""
                INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,cap_reason,source,source_id)
                VALUES (:id,:userId,'POST_LOCK_CORRECTION',:bizDate,:score,:score,:reason,'SETTLEMENT_CORRECTION',:sourceId)
                """).param("id", UUID.randomUUID()).param("userId", item.userId())
                .param("bizDate", month.atEndOfMonth()).param("score", score)
                .param("reason", "POST_LOCK_CORRECTION").param("sourceId", item.id()).update();
        BigDecimal bonus = item.adjustmentScore() > 0 ? score : BigDecimal.ZERO;
        BigDecimal penalty = item.adjustmentScore() < 0 ? score.abs() : BigDecimal.ZERO;
        jdbc.sql("""
                INSERT INTO score_summary(user_id,period,bonus,penalty,total)
                VALUES (:userId,:period,:bonus,:penalty,:score)
                ON CONFLICT (user_id,period) DO UPDATE SET
                  bonus=score_summary.bonus+:bonus,penalty=score_summary.penalty+:penalty,
                  total=score_summary.total+:score,rebuilt_at=CURRENT_TIMESTAMP
                """).param("userId", item.userId()).param("period", run.period())
                .param("bonus", bonus).param("penalty", penalty).param("score", score).update();
        jdbc.sql("UPDATE grade_snapshot SET status='SUPERSEDED' WHERE settlement_run_id=:runId AND status='PUBLISHED'")
                .param("runId", run.id()).update();
        jdbc.sql("UPDATE settlement_run SET status='CORRECTED' WHERE id=:runId AND status='PUBLISHED'")
                .param("runId", run.id()).update();
        jdbc.sql("UPDATE settlement_correction SET status='ACCEPTED',decided_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", item.id()).update();
        // A correction belongs to one immutable batch. Keep the other requests as
        // history and explicitly ask their initiators to resubmit against the new batch.
        jdbc.sql("SELECT id FROM settlement_correction WHERE settlement_run_id=:run AND status='PENDING'")
                .param("run",run.id()).query(UUID.class).list().forEach(id -> {
                    CorrectionView obsolete=get(id);
                    jdbc.sql("UPDATE settlement_correction SET status='SUPERSEDED',decided_at=CURRENT_TIMESTAMP WHERE id=:id")
                            .param("id",id).update();
                    notifications.create(obsolete.requestedBy(),"SETTLEMENT_CORRECTION_SUPERSEDED","更正需重新发起",
                            "原封存批次已被另一更正替代，请在新批次封存后重新核对并发起。","SETTLEMENT_CORRECTION",id,false);
                    audit.record(actor.userId(),"SETTLEMENT_CORRECTION_SUPERSEDED","SETTLEMENT_CORRECTION",id,"{}",requestId);
                });
        notifications.create(item.userId(), "SETTLEMENT_CORRECTION_ACCEPTED", "月度绩效正在更正",
                run.period() + "月封存结果已进入重新核算流程。", "SETTLEMENT_CORRECTION", item.id(), false);
        settlements.preview(actor, run.period(), run.orgId(), "CORR-" + run.period() + "-" + item.id().toString().substring(0, 6), requestId);
    }

    private CorrectionView getForUpdate(UUID id) {
        jdbc.sql("SELECT id FROM settlement_correction WHERE id=:id FOR UPDATE").param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException("CORRECTION_NOT_FOUND", "封存更正不存在", HttpStatus.NOT_FOUND));
        return get(id);
    }

    private CorrectionView get(UUID id) {
        return jdbc.sql("""
                SELECT c.id,c.settlement_run_id,r.period,c.user_id,u.employee_no,u.display_name,
                  c.reason,c.adjustment_score,c.status,c.requested_by,requester.display_name requester_name,
                  c.created_at,c.decided_at,
                  (SELECT COUNT(*) FROM settlement_correction_vote v WHERE v.correction_id=c.id AND v.decision='APPROVE') approvals,
                  (SELECT COUNT(*) FROM settlement_correction_vote v WHERE v.correction_id=c.id AND v.decision='REJECT') rejections
                  ,(SELECT COUNT(*) FROM settlement_correction_approver a WHERE a.correction_id=c.id) required_approvals
                FROM settlement_correction c JOIN settlement_run r ON r.id=c.settlement_run_id
                JOIN app_user u ON u.id=c.user_id JOIN app_user requester ON requester.id=c.requested_by
                WHERE c.id=:id
                """).param("id", id).query((rs, n) -> new CorrectionView(
                rs.getObject("id", UUID.class), rs.getObject("settlement_run_id", UUID.class),
                rs.getString("period"), rs.getObject("user_id", UUID.class), rs.getString("employee_no"),
                rs.getString("display_name"), rs.getString("reason"), rs.getInt("adjustment_score"),
                rs.getString("status"), rs.getObject("requested_by", UUID.class), rs.getString("requester_name"),
                rs.getInt("approvals"), rs.getInt("rejections"), rs.getInt("required_approvals"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("decided_at", java.time.OffsetDateTime.class))).optional()
                .orElseThrow(() -> new ApiException("CORRECTION_NOT_FOUND", "封存更正不存在", HttpStatus.NOT_FOUND));
    }

    private Run run(UUID id) {
        return jdbc.sql("SELECT id,period,org_unit_id,status FROM settlement_run WHERE id=:id")
                .param("id", id).query((rs, n) -> new Run(rs.getObject("id", UUID.class),
                        rs.getString("period"), rs.getObject("org_unit_id", UUID.class), rs.getString("status")))
                .optional().orElseThrow(() -> new ApiException("SETTLEMENT_NOT_FOUND", "结算批次不存在", HttpStatus.NOT_FOUND));
    }

    private int approvalCount(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM settlement_correction_vote v JOIN settlement_correction_approver a ON a.correction_id=v.correction_id AND a.user_id=v.supervisor_id WHERE v.correction_id=:id AND v.decision='APPROVE'")
                .param("id", id).query(Long.class).single().intValue();
    }

    private List<UUID> approvers(UUID id) {
        return jdbc.sql("SELECT user_id FROM settlement_correction_approver WHERE correction_id=:id").param("id",id).query(UUID.class).list();
    }

    private Run lockedRun(UUID id) {
        jdbc.sql("SELECT id FROM settlement_run WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional()
            .orElseThrow(()->new ApiException("SETTLEMENT_NOT_FOUND","结算批次不存在",HttpStatus.NOT_FOUND));
        return run(id);
    }

    private void requireSupervisor(CurrentUser actor) {
        if (!actor.roles().contains(BusinessRoles.SUPERVISOR))
            throw new ApiException("FORBIDDEN", "仅主管可以发起和表决封存更正", HttpStatus.FORBIDDEN);
    }

    private void requireViewer(CurrentUser actor) {
        if (!actor.roles().contains(BusinessRoles.SUPERVISOR) && !actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))
            throw new ApiException("FORBIDDEN", "无权查看封存更正", HttpStatus.FORBIDDEN);
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record Run(UUID id, String period, UUID orgId, String status) {}
    public record CorrectionView(UUID id, UUID runId, String period, UUID userId, String employeeNo,
                                 String displayName, String reason, int adjustmentScore, String status,
                                 UUID requestedBy, String requesterName, int approvals, int rejections, int requiredApprovals,
                                 java.time.OffsetDateTime createdAt, java.time.OffsetDateTime decidedAt) {}
}
