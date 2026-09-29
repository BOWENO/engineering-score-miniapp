package com.acme.performance.task.service;

import com.acme.performance.task.model.DashboardView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class DashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    public DashboardService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public DashboardView get(UUID userId) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        String period = YearMonth.from(today).toString();
        Summary summary = jdbc.sql("SELECT base,bonus,penalty,total FROM score_summary WHERE user_id=:userId AND period=:period")
                .param("userId", userId).param("period", period)
                .query((rs, rowNum) -> new Summary(rs.getBigDecimal("base"), rs.getBigDecimal("bonus"),
                        rs.getBigDecimal("penalty"), rs.getBigDecimal("total")))
                .optional().orElse(Summary.ZERO);
        List<DashboardView.TaskItem> tasks = jdbc.sql("""
                SELECT t.id,t.rule_code,COALESCE(r.title,t.rule_code) AS title,t.status,t.deadline_at,t.version
                FROM daily_task t
                LEFT JOIN score_rule r ON r.code=t.rule_code AND r.rule_version_id=t.rule_version_id
                WHERE t.user_id=:userId AND t.biz_date=:today
                ORDER BY CASE t.status WHEN 'PENDING' THEN 0 ELSE 1 END,t.deadline_at NULLS LAST,t.rule_code
                """).param("userId", userId).param("today", today)
                .query((rs, rowNum) -> new DashboardView.TaskItem(rs.getObject("id", UUID.class),
                        rs.getString("rule_code"), rs.getString("title"), rs.getString("status"),
                        rs.getObject("deadline_at", java.time.OffsetDateTime.class), rs.getLong("version"))).list();
        int completed = (int) tasks.stream().filter(task -> "COMPLETED".equals(task.status())).count();
        return new DashboardView(period, summary.total(), summary.base(), summary.bonus(), summary.penalty(),
                completed, tasks.size(), tasks);
    }

    private record Summary(BigDecimal base, BigDecimal bonus, BigDecimal penalty, BigDecimal total) {
        private static final Summary ZERO = new Summary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
