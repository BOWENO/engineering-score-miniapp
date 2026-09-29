package com.acme.performance.settlement.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GradeDistributionTest {
    private final GradeDistribution distribution = new GradeDistribution();

    @Test
    void largestRemainderAlwaysAllocatesEveryPerson() {
        for (int count = 0; count <= 101; count++) {
            assertThat(distribution.quotas(count).values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(count);
        }
    }

    @Test
    void confirmedDGradeIsRemovedFromNormalPool() {
        var normal = List.of(score(10), score(9), score(8));
        var d = List.of(score(100));
        var results = distribution.distribute(normal, d);
        assertThat(results).hasSize(4);
        assertThat(results.getLast().proposedGrade()).isEqualTo("D");
    }

    @Test
    void identicalFactorsAcrossGradeBoundaryRequireManualDecision() {
        var same = new GradeDistribution.ScoreInput(UUID.randomUUID(), value(10), value(8), value(2), value(0));
        var sameAgain = new GradeDistribution.ScoreInput(UUID.randomUUID(), value(10), value(8), value(2), value(0));
        var results = distribution.distribute(List.of(same, sameAgain), List.of());
        assertThat(results).allMatch(GradeDistribution.Result::manualRequired);
    }

    private GradeDistribution.ScoreInput score(int total) {
        return new GradeDistribution.ScoreInput(UUID.randomUUID(), value(total), value(total), value(0), value(0));
    }
    private BigDecimal value(int value) { return BigDecimal.valueOf(value); }
}
