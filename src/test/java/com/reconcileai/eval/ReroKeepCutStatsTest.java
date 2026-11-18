package com.reconcileai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReroKeepCutStatsTest {

    @Test
    void wilsonMatchesTheExampleInTheFrozenRule() {
        // RERO_EVALUATION_RULE.md 3.3: 27/28 -> 82.3% to 99.4%
        ReroKeepCutStats.Interval i = ReroKeepCutStats.wilson(27, 28);
        assertThat(i.low()).isCloseTo(0.8229, within(0.0005));
        assertThat(i.high()).isCloseTo(0.9937, within(0.0005));
        assertThat(i.percent()).isEqualTo("82.3% to 99.4%");
    }

    @Test
    void wilsonEdges() {
        assertThat(ReroKeepCutStats.wilson(0, 28).low()).isCloseTo(0.0, within(1e-12));
        assertThat(ReroKeepCutStats.wilson(0, 28).high()).isCloseTo(0.1206, within(0.0005));
        assertThat(ReroKeepCutStats.wilson(28, 28).high()).isEqualTo(1.0);
        assertThat(ReroKeepCutStats.wilson(28, 28).low()).isCloseTo(0.8794, within(0.0005));
        assertThat(ReroKeepCutStats.wilson(14, 28).low()).isCloseTo(0.3265, within(0.0005));
        assertThat(ReroKeepCutStats.wilson(0, 0).high()).isEqualTo(1.0);
        assertThatThrownBy(() -> ReroKeepCutStats.wilson(3, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactMcNemar() {
        assertThat(ReroKeepCutStats.mcnemarExact(0, 0)).isEqualTo(1.0);
        assertThat(ReroKeepCutStats.mcnemarExact(1, 0)).isEqualTo(1.0);
        assertThat(ReroKeepCutStats.mcnemarExact(1, 1)).isEqualTo(1.0);
        // 5 vs 0: 2 * 0.5^5 = 0.0625 (not significant); 6 vs 0: 2 * 0.5^6 = 0.03125 (the rule's "at least 6")
        assertThat(ReroKeepCutStats.mcnemarExact(5, 0)).isCloseTo(0.0625, within(1e-12));
        assertThat(ReroKeepCutStats.mcnemarExact(0, 6)).isCloseTo(0.03125, within(1e-12));
        // 2 vs 8: 2 * P(X <= 2 | n = 10) = 2 * 56 / 1024
        assertThat(ReroKeepCutStats.mcnemarExact(2, 8)).isCloseTo(112.0 / 1024, within(1e-12));
        assertThat(ReroKeepCutStats.mcnemarExact(3, 4)).isEqualTo(1.0);
    }

    private static ReroKeepCutReport.Row row(String id, String engine, String cause, String route, long ms, String usd,
                                             int missing) {
        return new ReroKeepCutReport.Row(id, engine, "j-" + id, cause != null, cause == null ? "INVALID_CITATION" : null,
            "FEE", cause, "L1_APPROVAL", route, cause == null ? null : true, 1, missing, ms, ms,
            new java.math.BigDecimal(usd), 1, "SUCCEEDED", 3, 8, false, null);
    }

    @Test
    void comparisonAppliesTheFrozenThresholds() {
        List<ReroKeepCutReport.Row> base = List.of(row("A", "SINGLE_CALL", "FEE", "L1_APPROVAL", 1000, "0.001", 0),
            row("B", "SINGLE_CALL", "FEE", "L1_APPROVAL", 1000, "0.001", 0),
            row("C", "SINGLE_CALL", "TIMING", "MANUAL_REVIEW", 1000, "0.001", 1));
        List<ReroKeepCutReport.Row> fine = List.of(row("A", "RERO", "FEE", "L1_APPROVAL", 3000, "0.004", 0),
            row("B", "RERO", "FEE", "L1_APPROVAL", 3000, "0.004", 0),
            row("C", "RERO", "TIMING", "MANUAL_REVIEW", 3000, "0.004", 1));
        assertThat(ReroKeepCutReport.compare(base, fine, true, 0).allPass()).isTrue();
        // safety ITs not run -> C1 fails, so CUT
        assertThat(ReroKeepCutReport.compare(base, fine, null, 0).allPass()).isFalse();

        // two baseline-only right cases (C2), wrong cause to approver where baseline went to manual review (C1),
        // 6x cost (C4), p95 over 4x (C5)
        List<ReroKeepCutReport.Row> bad = List.of(row("A", "RERO", null, null, 5000, "0.006", 1),
            row("B", "RERO", "TIMING", "L1_APPROVAL", 5000, "0.006", 1),
            row("C", "RERO", "DUPLICATE", "L1_APPROVAL", 5000, "0.006", 1));
        ReroKeepCutReport.Comparison c = ReroKeepCutReport.compare(base, bad, true, 1);
        assertThat(c.criteria()).extracting(ReroKeepCutReport.Criterion::id, ReroKeepCutReport.Criterion::pass)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("C1", false), org.assertj.core.groups.Tuple.tuple("C2", false),
                org.assertj.core.groups.Tuple.tuple("C3", false), org.assertj.core.groups.Tuple.tuple("C4", false),
                org.assertj.core.groups.Tuple.tuple("C5", false), org.assertj.core.groups.Tuple.tuple("C6", false));
    }

    @Test
    void nearestRankP95() {
        List<Long> twentyEight = new java.util.ArrayList<>();
        for (long i = 1; i <= 28; i++) {
            twentyEight.add(i * 100);
        }
        java.util.Collections.shuffle(twentyEight, new java.util.Random(7));
        // ceil(0.95 * 28) = 27 -> the 27th smallest
        assertThat(ReroKeepCutStats.nearestRank(twentyEight, 95)).isEqualTo(2700);
        assertThat(ReroKeepCutStats.nearestRank(List.of(5L), 95)).isEqualTo(5);
        assertThat(ReroKeepCutStats.nearestRank(List.of(3L, 1L, 2L), 100)).isEqualTo(3);
        assertThatThrownBy(() -> ReroKeepCutStats.nearestRank(List.of(), 95)).isInstanceOf(IllegalArgumentException.class);
    }
}
