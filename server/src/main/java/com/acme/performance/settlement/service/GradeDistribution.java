package com.acme.performance.settlement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class GradeDistribution {
    private static final List<GradeRatio> RATIOS = List.of(
            new GradeRatio("S", 5), new GradeRatio("A", 20), new GradeRatio("B1", 20),
            new GradeRatio("B2", 30), new GradeRatio("B3", 20), new GradeRatio("C", 5));
    private static final Comparator<ScoreInput> ORDER = Comparator.comparing(ScoreInput::total).reversed()
            .thenComparing(ScoreInput::penalty)
            .thenComparing(ScoreInput::base, Comparator.reverseOrder())
            .thenComparing(ScoreInput::bonus, Comparator.reverseOrder())
            .thenComparing(input -> input.userId().toString());

    public List<Result> distribute(List<ScoreInput> normal, List<ScoreInput> dGrade) {
        List<ScoreInput> sorted = normal.stream().sorted(ORDER).toList();
        Map<String, Integer> quotas = quotas(sorted.size());
        List<Result> results = new ArrayList<>();
        int position = 0;
        for (GradeRatio ratio : RATIOS) {
            for (int i = 0; i < quotas.get(ratio.grade()) && position < sorted.size(); i++) {
                results.add(result(sorted.get(position), ratio.grade(), position + 1, false)); position++;
            }
        }
        markUnresolvedBoundaryTies(results);
        List<ScoreInput> dSorted = dGrade.stream().sorted(ORDER).toList();
        for (int i = 0; i < dSorted.size(); i++) results.add(result(dSorted.get(i), "D", sorted.size() + i + 1, false));
        return results;
    }

    Map<String, Integer> quotas(int count) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int cumulativePercent = 0;
        int assigned = 0;
        for (GradeRatio ratio : RATIOS) {
            cumulativePercent += ratio.percent();
            int cumulativeQuota = BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(cumulativePercent))
                    .divide(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
            result.put(ratio.grade(), cumulativeQuota - assigned);
            assigned = cumulativeQuota;
        }
        return result;
    }

    private void markUnresolvedBoundaryTies(List<Result> results) {
        for (int boundary = 1; boundary < results.size(); boundary++) {
            Result left = results.get(boundary - 1), right = results.get(boundary);
            if (!left.proposedGrade().equals(right.proposedGrade()) && sameFactors(left, right)) {
                int start = boundary - 1, end = boundary;
                while (start > 0 && sameFactors(results.get(start - 1), left)) start--;
                while (end + 1 < results.size() && sameFactors(results.get(end + 1), right)) end++;
                for (int i = start; i <= end; i++) results.set(i, results.get(i).withManualRequired());
            }
        }
    }

    private boolean sameFactors(Result a, Result b) {
        return a.total().compareTo(b.total()) == 0 && a.penalty().compareTo(b.penalty()) == 0
                && a.base().compareTo(b.base()) == 0 && a.bonus().compareTo(b.bonus()) == 0;
    }
    private Result result(ScoreInput input, String grade, int rank, boolean manual) {
        return new Result(input.userId(), input.total(), input.base(), input.bonus(), input.penalty(), grade, rank, manual);
    }

    private record GradeRatio(String grade, int percent) {}
    public record ScoreInput(UUID userId, BigDecimal total, BigDecimal base, BigDecimal bonus, BigDecimal penalty) {}
    public record Result(UUID userId, BigDecimal total, BigDecimal base, BigDecimal bonus, BigDecimal penalty,
                         String proposedGrade, int rank, boolean manualRequired) {
        Result withManualRequired() { return new Result(userId,total,base,bonus,penalty,proposedGrade,rank,true); }
    }
}
