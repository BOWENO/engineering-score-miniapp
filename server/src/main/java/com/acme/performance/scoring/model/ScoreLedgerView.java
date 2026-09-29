package com.acme.performance.scoring.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ScoreLedgerView(
        String period,
        BigDecimal total,
        BigDecimal base,
        BigDecimal bonus,
        BigDecimal penalty,
        List<Item> items) {

    public record Item(
            UUID id,
            String ruleCode,
            String title,
            LocalDate businessDate,
            BigDecimal score,
            String source,
            Instant createdAt) {}
}
