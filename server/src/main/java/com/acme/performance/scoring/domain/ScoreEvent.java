package com.acme.performance.scoring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "score_event")
public class ScoreEvent {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "rule_code", nullable = false, updatable = false)
    private String ruleCode;
    @Column(name = "biz_date", nullable = false, updatable = false)
    private LocalDate businessDate;
    @Column(name = "original_score", nullable = false, updatable = false)
    private BigDecimal originalScore;
    @Column(name = "actual_score", nullable = false, updatable = false)
    private BigDecimal actualScore;
    @Column(nullable = false, updatable = false)
    private String source;
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;
    @Column(name = "original_event_id", updatable = false)
    private UUID originalEventId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScoreEvent() {}

    public ScoreEvent(UUID userId, String ruleCode, LocalDate businessDate, BigDecimal originalScore,
                      BigDecimal actualScore, String source, UUID sourceId, UUID originalEventId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.ruleCode = ruleCode;
        this.businessDate = businessDate;
        this.originalScore = originalScore;
        this.actualScore = actualScore;
        this.source = source;
        this.sourceId = sourceId;
        this.originalEventId = originalEventId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public BigDecimal getActualScore() { return actualScore; }
}
