package com.acme.performance.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

@Component
public class ReviewDemoDataInitializer implements ApplicationRunner {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final AdminAuthService auth;
    private final String configuredPassword;

    public ReviewDemoDataInitializer(JdbcClient jdbc, AdminAuthService auth,
                                     @Value("${review.account.password:}") String configuredPassword) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID departmentId = jdbc.sql("SELECT id FROM org_unit WHERE type='DEPARTMENT' AND name='测试工程部' ORDER BY created_at LIMIT 1")
                .query(UUID.class).optional().orElse(null);
        UUID administratorId = jdbc.sql("SELECT id FROM app_user WHERE is_administrator=TRUE AND status='ACTIVE' ORDER BY created_at LIMIT 1")
                .query(UUID.class).optional().orElse(null);
        if (departmentId == null || administratorId == null) return;

        UUID reviewUserId = jdbc.sql("SELECT id FROM app_user WHERE lower(employee_no)='wxreview'")
                .query(UUID.class).optional().orElse(null);
        if (reviewUserId == null && configuredPassword.isBlank()) return;

        UUID teamId = jdbc.sql("SELECT id FROM org_unit WHERE is_review_data=TRUE AND type='TEAM' ORDER BY created_at LIMIT 1")
                .query(UUID.class).optional().orElseGet(() -> {
                    UUID id = stable("review-team");
                    jdbc.sql("INSERT INTO org_unit(id,parent_id,type,name,is_review_data) VALUES (:id,:parentId,'TEAM','微信审核演示组',TRUE) ON CONFLICT (id) DO NOTHING")
                            .param("id", id).param("parentId", departmentId).update();
                    return id;
                });

        if (reviewUserId == null) {
            reviewUserId = stable("review-user");
            jdbc.sql("""
                    INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_review_account,password_change_required)
                    VALUES (:id,'wxreview','微信审核体验账号',:teamId,'ACTIVE',TRUE,FALSE)
                    """).param("id", reviewUserId).param("teamId", teamId).update();
        } else {
            jdbc.sql("UPDATE app_user SET org_unit_id=:teamId,status='ACTIVE',is_review_account=TRUE,password_change_required=FALSE WHERE id=:id")
                    .param("teamId", teamId).param("id", reviewUserId).update();
        }

        UUID credentialUserId = reviewUserId;
        long credential = jdbc.sql("SELECT COUNT(*) FROM admin_credential WHERE user_id=:id")
                .param("id", reviewUserId).query(Long.class).single();
        if (credential == 0 && !configuredPassword.isBlank()) {
            jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:id,'wxreview',:hash)")
                    .param("id", credentialUserId).param("hash", auth.encodePassword(configuredPassword)).update();
        }
        jdbc.sql("DELETE FROM role_binding WHERE user_id=:id").param("id", reviewUserId).update();
        jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:userId,'TECHNICIAN',:teamId)")
                .param("id", stable("review-role")).param("userId", reviewUserId).param("teamId", teamId).update();

        UUID lineId = stable("review-line");
        UUID stationId = stable("review-station");
        UUID equipmentId = stable("review-equipment");
        jdbc.sql("""
                INSERT INTO production_line(id,org_unit_id,code,name,status,is_review_data)
                VALUES (:id,:teamId,'WXREVIEW_LINE','审核演示线','ACTIVE',TRUE) ON CONFLICT (id) DO NOTHING
                """).param("id", lineId).param("teamId", teamId).update();
        jdbc.sql("""
                INSERT INTO station(id,line_id,code,name,status)
                VALUES (:id,:lineId,'WXREVIEW_STATION','功能测试站','ACTIVE') ON CONFLICT (id) DO NOTHING
                """).param("id", stationId).param("lineId", lineId).update();
        jdbc.sql("""
                INSERT INTO equipment(id,org_unit_id,line_id,station_id,code,name,category,status,is_review_data)
                VALUES (:id,:teamId,:lineId,:stationId,'WX-DEVICE-01','审核演示设备','测试设备','ACTIVE',TRUE)
                ON CONFLICT (id) DO NOTHING
                """).param("id", equipmentId).param("teamId", teamId).param("lineId", lineId)
                .param("stationId", stationId).update();

        Shift shift = jdbc.sql("""
                SELECT id,starts_at,ends_at FROM shift_definition
                WHERE code='DAY' AND status='ACTIVE' AND effective_from<=CURRENT_DATE
                  AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)
                ORDER BY effective_from DESC LIMIT 1
                """).query((rs, n) -> new Shift(rs.getObject("id", UUID.class),
                rs.getObject("starts_at", LocalTime.class), rs.getObject("ends_at", LocalTime.class))).optional().orElse(null);
        if (shift == null) return;

        LocalDate today = LocalDate.now(ZONE);
        for (int offset = -1; offset <= 5; offset++) {
            LocalDate date = today.plusDays(offset);
            UUID scheduleId = stable("review-schedule-" + date);
            OffsetDateTime starts = date.atTime(shift.startsAt()).atZone(ZONE).toOffsetDateTime();
            OffsetDateTime ends = date.atTime(shift.endsAt()).atZone(ZONE).toOffsetDateTime();
            jdbc.sql("""
                    INSERT INTO schedule_assignment(id,user_id,team_id,business_date,shift_id,shift_code,shift_starts_at,
                      shift_ends_at,line_id,station_id,status,created_by,published_by,published_at,acknowledged_at)
                    VALUES (:id,:userId,:teamId,:date,:shiftId,'DAY',:starts,:ends,:lineId,:stationId,'PUBLISHED',
                      :adminId,:adminId,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    ON CONFLICT (user_id,business_date,shift_code,line_id,station_id) DO NOTHING
                    """).param("id", scheduleId).param("userId", reviewUserId).param("teamId", teamId)
                    .param("date", date).param("shiftId", shift.id()).param("starts", starts).param("ends", ends)
                    .param("lineId", lineId).param("stationId", stationId).param("adminId", administratorId).update();
        }

        YearMonth month = YearMonth.now(ZONE);
        for (int day = 1; day <= 3; day++) {
            LocalDate date = month.atDay(day);
            UUID shiftScoreId = stable("review-shift-score-" + month + "-" + day);
            jdbc.sql("""
                    INSERT INTO shift_score(id,user_id,business_date,shift_code,shift_ends_at,base_score,status,posted_at)
                    VALUES (:id,:userId,:date,'DAY',:ends,10,'POSTED',CURRENT_TIMESTAMP)
                    ON CONFLICT (user_id,business_date,shift_code) DO NOTHING
                    """).param("id", shiftScoreId).param("userId", reviewUserId).param("date", date)
                    .param("ends", date.atTime(shift.endsAt()).atZone(ZONE).toOffsetDateTime()).update();
            jdbc.sql("""
                    INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,source,source_id)
                    VALUES (:id,:userId,'BASE_SHIFT',:date,10,10,'SHIFT_SCORE',:sourceId)
                    ON CONFLICT (source,source_id,rule_code) DO NOTHING
                    """).param("id", stable("review-score-event-" + month + "-" + day))
                    .param("userId", reviewUserId).param("date", date).param("sourceId", shiftScoreId).update();
        }

        UUID bonusCaseId = stable("review-bonus-" + month);
        jdbc.sql("""
                INSERT INTO performance_case(id,case_type,target_user_id,initiated_by,occurred_at,description,rule_code,
                  suggested_score,approved_score,effective_score,status,current_stage,direct_by_supervisor)
                VALUES (:id,'BONUS',:userId,:userId,:occurredAt,'微信审核演示：主动发现设备参数异常并及时处理',
                  'DEMO_BONUS',2,2,2,'EFFECTIVE',NULL,FALSE) ON CONFLICT (id) DO NOTHING
                """).param("id", bonusCaseId).param("userId", reviewUserId)
                .param("occurredAt", month.atDay(2).atTime(12, 0).atZone(ZONE).toOffsetDateTime()).update();
        jdbc.sql("""
                INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,source,source_id)
                VALUES (:id,:userId,'DEMO_BONUS',:date,2,2,'PERFORMANCE_CASE',:sourceId)
                ON CONFLICT (source,source_id,rule_code) DO NOTHING
                """).param("id", stable("review-bonus-event-" + month)).param("userId", reviewUserId)
                .param("date", month.atDay(2)).param("sourceId", bonusCaseId).update();
        jdbc.sql("""
                INSERT INTO score_summary(user_id,period,base,bonus,penalty,total)
                VALUES (:userId,:period,30,2,0,32)
                ON CONFLICT (user_id,period) DO UPDATE SET base=30,bonus=2,penalty=0,total=32,rebuilt_at=CURRENT_TIMESTAMP
                """).param("userId", reviewUserId).param("period", month.toString()).update();

        seedIncident(month, reviewUserId, teamId, lineId, stationId, equipmentId);
    }

    private void seedIncident(YearMonth month, UUID userId, UUID teamId, UUID lineId,
                              UUID stationId, UUID equipmentId) {
        UUID incidentId = stable("review-incident-" + month);
        UUID statementId = stable("review-statement-" + month);
        OffsetDateTime occurredAt = month.atDay(Math.min(4, month.lengthOfMonth()))
                .atTime(10, 15).atZone(ZONE).toOffsetDateTime();
        jdbc.sql("""
                INSERT INTO equipment_incident(id,incident_no,occurred_at,business_date,shift_code,team_id,line_id,
                  station_id,status,created_by,archived_at)
                VALUES (:id,:no,:occurredAt,:date,'DAY',:teamId,:lineId,:stationId,'ARCHIVED',:userId,CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """).param("id", incidentId).param("no", "WX" + month.toString().replace("-", ""))
                .param("occurredAt", occurredAt).param("date", occurredAt.toLocalDate()).param("teamId", teamId)
                .param("lineId", lineId).param("stationId", stationId).param("userId", userId).update();
        jdbc.sql("INSERT INTO incident_equipment(incident_id,equipment_id) VALUES (:incidentId,:equipmentId) ON CONFLICT DO NOTHING")
                .param("incidentId", incidentId).param("equipmentId", equipmentId).update();
        jdbc.sql("""
                INSERT INTO incident_statement(id,incident_id,responsible_user_id,phenomenon,handling_method,root_cause,
                  long_term_action,status,due_at,submitted_at,reviewed_at)
                VALUES (:id,:incidentId,:userId,'设备启动后参数显示异常','重新校准传感器并复核参数','传感器零点漂移',
                  '将零点校验加入每周点检表','APPROVED',:dueAt,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT (incident_id,responsible_user_id) DO NOTHING
                """).param("id", statementId).param("incidentId", incidentId).param("userId", userId)
                .param("dueAt", occurredAt.plusHours(24)).update();
    }

    private UUID stable(String value) {
        return UUID.nameUUIDFromBytes(("engineering-score:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private record Shift(UUID id, LocalTime startsAt, LocalTime endsAt) {}
}
