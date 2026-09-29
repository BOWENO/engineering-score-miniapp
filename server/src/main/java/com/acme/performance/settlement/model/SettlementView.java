package com.acme.performance.settlement.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SettlementView(UUID runId, String period, String version, String status, long manualPending,
                             int confirmations, int requiredConfirmations,
                             List<Candidate> candidates) {
    public record Candidate(UUID userId, String displayName, String employeeNo, BigDecimal total,
                            BigDecimal base, BigDecimal bonus, BigDecimal penalty, int shiftCount, BigDecimal averageScore,
                            String proposedGrade, String finalGrade, int rank, boolean manualRequired) {}
}
