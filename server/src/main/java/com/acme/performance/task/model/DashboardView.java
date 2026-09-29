package com.acme.performance.task.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DashboardView(
        String period,
        BigDecimal total,
        BigDecimal base,
        BigDecimal bonus,
        BigDecimal penalty,
        int completedTasks,
        int totalTasks,
        List<TaskItem> tasks) {
    public record TaskItem(UUID id, String ruleCode, String title, String status, OffsetDateTime deadlineAt, long version) {}
}
