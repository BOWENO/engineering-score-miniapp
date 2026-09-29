package com.acme.performance.task.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class DailyTaskGenerator {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    public DailyTaskGenerator(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void generateToday() {
        generate(LocalDate.now(BUSINESS_ZONE));
    }

    @Transactional
    public int generate(LocalDate businessDate) {
        List<Candidate> candidates = jdbc.sql("""
                SELECT u.id AS user_id,r.code AS rule_code,rv.id AS rule_version_id
                FROM app_user u CROSS JOIN score_rule r JOIN rule_version rv ON rv.id=r.rule_version_id
                WHERE u.status='ACTIVE' AND r.type='BASE' AND rv.status='PUBLISHED'
                  AND rv.effective_at<=CURRENT_TIMESTAMP
                  AND r.effective_from<=:bizDate AND (r.effective_to IS NULL OR r.effective_to>=:bizDate)
                """).param("bizDate", businessDate)
                .query((rs, rowNum) -> new Candidate(rs.getObject("user_id", UUID.class), rs.getString("rule_code"),
                        rs.getObject("rule_version_id", UUID.class))).list();
        OffsetDateTime deadline = businessDate.atTime(LocalTime.of(23, 59, 59)).atZone(BUSINESS_ZONE).toOffsetDateTime();
        int inserted = 0;
        for (Candidate candidate : candidates) {
            inserted += jdbc.sql("""
                    INSERT INTO daily_task(id,user_id,biz_date,rule_code,rule_version_id,status,deadline_at)
                    VALUES (:id,:userId,:bizDate,:ruleCode,:ruleVersionId,'PENDING',:deadline)
                    ON CONFLICT (user_id,biz_date,rule_code) DO NOTHING
                    """).param("id", UUID.randomUUID()).param("userId", candidate.userId())
                    .param("bizDate", businessDate).param("ruleCode", candidate.ruleCode())
                    .param("ruleVersionId", candidate.ruleVersionId())
                    .param("deadline", deadline).update();
        }
        return inserted;
    }

    private record Candidate(UUID userId, String ruleCode, UUID ruleVersionId) {}
}
