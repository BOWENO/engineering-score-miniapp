package com.acme.performance.scoring.service;

import com.acme.performance.scoring.model.ScoreLedgerView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
public class ScoreLedgerService {
    private final JdbcClient jdbc;

    public ScoreLedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ScoreLedgerView get(UUID userId, YearMonth period) {
        String periodText = period.toString();
        Summary summary = jdbc.sql("SELECT base,bonus,penalty,total FROM score_summary WHERE user_id=:userId AND period=:period")
                .param("userId", userId)
                .param("period", periodText)
                .query((rs, rowNum) -> new Summary(
                        rs.getBigDecimal("total"), rs.getBigDecimal("base"),
                        rs.getBigDecimal("bonus"), rs.getBigDecimal("penalty")))
                .optional().orElse(Summary.ZERO);

        List<ScoreLedgerView.Item> items = jdbc.sql("""
                SELECT e.id,e.rule_code,
                       COALESCE((SELECT r.title FROM score_rule r
                                 WHERE r.code=e.rule_code AND r.effective_from<=e.biz_date
                                   AND (r.effective_to IS NULL OR r.effective_to>=e.biz_date)
                                 ORDER BY r.effective_from DESC LIMIT 1),e.rule_code) AS title,
                       e.biz_date,e.actual_score,e.source,e.created_at
                FROM score_event e
                WHERE e.user_id=:userId AND e.biz_date>=:periodStart AND e.biz_date<:periodEnd
                ORDER BY e.biz_date DESC,e.created_at DESC
                """)
                .param("userId", userId)
                .param("periodStart", period.atDay(1))
                .param("periodEnd", period.plusMonths(1).atDay(1))
                .query((rs, rowNum) -> new ScoreLedgerView.Item(
                        rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("title"),
                        rs.getDate("biz_date").toLocalDate(), rs.getBigDecimal("actual_score"),
                        rs.getString("source"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return new ScoreLedgerView(periodText, summary.total(), summary.base(), summary.bonus(), summary.penalty(), items);
    }

    private record Summary(BigDecimal total, BigDecimal base, BigDecimal bonus, BigDecimal penalty) {
        private static final Summary ZERO = new Summary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
