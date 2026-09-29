package com.acme.performance.notification.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class DeadlineReminderScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final NotificationService notifications;

    public DeadlineReminderScheduler(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${performance.reminder-delay-ms:300000}")
    @Transactional
    public void sendDeadlineReminders() {
        remind(120, "2小时");
        remind(30, "30分钟");
    }

    private void remind(int minutes, String label) {
        OffsetDateTime threshold = OffsetDateTime.now(ZONE).plusMinutes(minutes);
        remindPerformanceCases(threshold, minutes, label);
        remindPerformanceAppeals(threshold, minutes, label);
        remindIncidentStatements(threshold, minutes, label);
        remindAppealWindows(threshold, minutes, label);
        remindDGrade(threshold, minutes, label);
    }

    private void remindPerformanceCases(OffsetDateTime threshold, int minutes, String label) {
        List<CaseDue> rows = jdbc.sql("""
                SELECT c.id,c.current_stage,u.org_unit_id AS target_team_id,u.display_name,c.case_type
                FROM performance_case c JOIN app_user u ON u.id=c.target_user_id
                WHERE c.status IN ('PENDING_ASSISTANT','PENDING_SUPERVISOR')
                  AND c.due_at>CURRENT_TIMESTAMP AND c.due_at<=:threshold
                """).param("threshold", threshold).query((rs, n) -> new CaseDue(
                rs.getObject("id", UUID.class), rs.getString("current_stage"),
                rs.getObject("target_team_id", UUID.class), rs.getString("display_name"),
                rs.getString("case_type"))).list();
        for (CaseDue row : rows) {
            List<UUID> recipients;
            if ("ASSISTANT".equals(row.stage())) {
                recipients = jdbc.sql("""
                        SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id
                        WHERE rb.role_code='ASSISTANT_ENGINEER' AND u.org_unit_id=:teamId AND u.status='ACTIVE'
                        """).param("teamId", row.teamId()).query(UUID.class).list();
            } else {
                recipients = activeSupervisors();
            }
            for (UUID userId : recipients) {
                notifications.createOnce(userId, "PERFORMANCE_DUE_" + minutes, "绩效审核即将逾期",
                        row.targetName() + "的" + caseLabel(row.caseType()) + "还剩" + label + "，请及时处理。",
                        "PERFORMANCE_CASE", row.id(), false);
            }
        }
    }

    private void remindPerformanceAppeals(OffsetDateTime threshold, int minutes, String label) {
        List<AppealDue> rows = jdbc.sql("""
                SELECT a.id,c.original_supervisor_id,u.display_name
                FROM performance_appeal a JOIN performance_case c ON c.id=a.case_id
                JOIN app_user u ON u.id=a.applicant_id
                WHERE a.status='PENDING' AND a.due_at>CURRENT_TIMESTAMP AND a.due_at<=:threshold
                """).param("threshold", threshold).query((rs, n) -> new AppealDue(
                rs.getObject("id", UUID.class), rs.getObject("original_supervisor_id", UUID.class),
                rs.getString("display_name"))).list();
        for (AppealDue row : rows) {
            List<UUID> recipients = jdbc.sql("""
                    SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id
                    WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE' AND rb.user_id<>:excluded
                      AND NOT EXISTS (SELECT 1 FROM performance_appeal_vote v WHERE v.appeal_id=:appealId AND v.supervisor_id=rb.user_id)
                    """).param("excluded", row.excludedSupervisor()).param("appealId", row.id()).query(UUID.class).list();
            for (UUID userId : recipients) {
                notifications.createOnce(userId, "APPEAL_DUE_" + minutes, "扣分申诉表决即将逾期",
                        row.applicantName() + "的申诉还剩" + label + "，请完成表决。",
                        "PERFORMANCE_APPEAL", row.id(), false);
            }
        }
    }

    private void remindIncidentStatements(OffsetDateTime threshold, int minutes, String label) {
        List<StatementDue> rows = jdbc.sql("""
                SELECT s.id,s.incident_id,s.responsible_user_id,i.incident_no,
                  CASE WHEN s.status='RETURNED' THEN s.resubmit_due_at ELSE s.due_at END AS deadline
                FROM incident_statement s JOIN equipment_incident i ON i.id=s.incident_id
                WHERE s.status IN ('PENDING','RETURNED')
                  AND CASE WHEN s.status='RETURNED' THEN s.resubmit_due_at ELSE s.due_at END>CURRENT_TIMESTAMP
                  AND CASE WHEN s.status='RETURNED' THEN s.resubmit_due_at ELSE s.due_at END<=:threshold
                """).param("threshold", threshold).query((rs, n) -> new StatementDue(
                rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getObject("responsible_user_id", UUID.class), rs.getString("incident_no"))).list();
        for (StatementDue row : rows) {
            notifications.createOnce(row.userId(), "INCIDENT_DUE_" + minutes, "异常说明即将逾期",
                    row.incidentNo() + " 的个人说明还剩" + label + "，请尽快提交。",
                    "INCIDENT_STATEMENT", row.statementId(), false);
        }
    }

    private void remindAppealWindows(OffsetDateTime threshold, int minutes, String label) {
        List<UserDue> rows = jdbc.sql("""
                SELECT c.id,c.target_user_id,u.display_name FROM performance_case c
                JOIN app_user u ON u.id=c.target_user_id
                WHERE c.status='APPROVED' AND c.case_type IN ('BASE_DEDUCTION','SPECIAL_DEDUCTION')
                  AND c.appeal_deadline_at>CURRENT_TIMESTAMP AND c.appeal_deadline_at<=:threshold
                  AND NOT EXISTS (SELECT 1 FROM performance_appeal a WHERE a.case_id=c.id)
                """).param("threshold", threshold).query((rs, n) -> new UserDue(
                rs.getObject("id", UUID.class), rs.getObject("target_user_id", UUID.class),
                rs.getString("display_name"))).list();
        for (UserDue row : rows) {
            notifications.createOnce(row.userId(), "DEDUCTION_APPEAL_WINDOW_" + minutes, "扣分申诉时限提醒",
                    "该扣分的申诉窗口还剩" + label + "，每条记录只能申诉一次。",
                    "PERFORMANCE_CASE", row.id(), false);
        }
    }

    private void remindDGrade(OffsetDateTime threshold, int minutes, String label) {
        List<UserDue> windows = jdbc.sql("""
                SELECT n.id,n.user_id,u.display_name FROM d_grade_nomination n JOIN app_user u ON u.id=n.user_id
                WHERE n.status='CONFIRMED' AND n.appeal_deadline_at>CURRENT_TIMESTAMP
                  AND n.appeal_deadline_at<=:threshold
                  AND NOT EXISTS (SELECT 1 FROM d_grade_appeal a WHERE a.nomination_id=n.id)
                """).param("threshold", threshold).query((rs, n) -> new UserDue(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("display_name"))).list();
        for (UserDue row : windows) {
            notifications.createOnce(row.userId(), "D_GRADE_APPEAL_WINDOW_" + minutes, "D级申诉时限提醒",
                    "D级认定的申诉窗口还剩" + label + "。", "D_GRADE_NOMINATION", row.id(), false);
        }

        List<NamedDue> appeals = jdbc.sql("""
                SELECT a.id,u.display_name FROM d_grade_appeal a JOIN app_user u ON u.id=a.applicant_id
                WHERE a.status='PENDING' AND a.due_at>CURRENT_TIMESTAMP AND a.due_at<=:threshold
                """).param("threshold", threshold).query((rs, n) -> new NamedDue(
                rs.getObject("id", UUID.class), rs.getString("display_name"))).list();
        for (NamedDue row : appeals) {
            List<UUID> recipients = jdbc.sql("""
                    SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id
                    WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'
                      AND NOT EXISTS (SELECT 1 FROM d_grade_appeal_vote v WHERE v.appeal_id=:appealId AND v.supervisor_id=rb.user_id)
                    """).param("appealId", row.id()).query(UUID.class).list();
            for (UUID userId : recipients) {
                notifications.createOnce(userId, "D_GRADE_APPEAL_DUE_" + minutes, "D级申诉表决即将逾期",
                        row.name() + "的D级申诉还剩" + label + "，请完成表决。",
                        "D_GRADE_APPEAL", row.id(), false);
            }
        }
    }

    private List<UUID> activeSupervisors() {
        return jdbc.sql("""
                SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id
                WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'
                """).query(UUID.class).list();
    }

    private String caseLabel(String type) {
        return switch (type) {
            case "BONUS" -> "加分申请";
            case "ADMONITION" -> "劝诫记录";
            default -> "扣分事项";
        };
    }

    private record CaseDue(UUID id, String stage, UUID teamId, String targetName, String caseType) {}
    private record AppealDue(UUID id, UUID excludedSupervisor, String applicantName) {}
    private record StatementDue(UUID statementId, UUID incidentId, UUID userId, String incidentNo) {}
    private record UserDue(UUID id, UUID userId, String name) {}
    private record NamedDue(UUID id, String name) {}
}
