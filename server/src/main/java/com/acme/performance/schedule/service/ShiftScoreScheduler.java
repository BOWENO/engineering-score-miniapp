package com.acme.performance.schedule.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class ShiftScoreScheduler {
    private final JdbcClient jdbc;
    public ShiftScoreScheduler(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${performance.shift-score-delay-ms:300000}")
    @Transactional
    public void postCompletedShifts() {
        List<Row> rows = jdbc.sql("""
                SELECT id,user_id,business_date,shift_code,base_score FROM shift_score
                WHERE status='PENDING' AND shift_ends_at<=CURRENT_TIMESTAMP ORDER BY shift_ends_at LIMIT 500
                FOR UPDATE SKIP LOCKED
                """).query((rs,n) -> new Row(rs.getObject("id", UUID.class),rs.getObject("user_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class),rs.getString("shift_code"),rs.getInt("base_score"))).list();
        for (Row row : rows) post(row);
    }

    private void post(Row row) {
        int inserted = jdbc.sql("""
                INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,source,source_id)
                VALUES (:id,:userId,'BASE_SHIFT',:bizDate,:score,:score,'SHIFT_SCORE',:sourceId)
                ON CONFLICT (source,source_id,rule_code) DO NOTHING
                """).param("id", UUID.randomUUID()).param("userId", row.userId()).param("bizDate", row.businessDate())
                .param("score", BigDecimal.valueOf(row.baseScore())).param("sourceId", row.id()).update();
        if (inserted == 1) {
            String period = row.businessDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            jdbc.sql("""
                    INSERT INTO score_summary(user_id,period,base,total) VALUES (:userId,:period,:score,:score)
                    ON CONFLICT (user_id,period) DO UPDATE SET base=score_summary.base+EXCLUDED.base,
                      total=score_summary.total+EXCLUDED.total,rebuilt_at=CURRENT_TIMESTAMP
                    """).param("userId", row.userId()).param("period", period)
                    .param("score", BigDecimal.valueOf(row.baseScore())).update();
        }
        jdbc.sql("UPDATE shift_score SET status='POSTED',posted_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", row.id()).update();
    }

    private record Row(UUID id, UUID userId, LocalDate businessDate, String shiftCode, int baseScore) {}
}
