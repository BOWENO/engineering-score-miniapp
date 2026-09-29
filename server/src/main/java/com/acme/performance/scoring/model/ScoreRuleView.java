package com.acme.performance.scoring.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ScoreRuleView(UUID id, String code, String title, String type, BigDecimal score,
                            JsonNode capPolicy, JsonNode evidenceSchema, JsonNode approvalFlow,
                            LocalDate effectiveFrom, LocalDate effectiveTo) {
}
