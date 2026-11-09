package com.reconcileai.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Headline numbers for one run, computed from the per-case results. */
public record EvalSummary(
    int cases,
    double jobSuccessRate,
    double rootCauseAccuracy,
    double routeAccuracy,
    double citationValidRate,
    double citationRecall,
    long p50WallMs,
    long p95WallMs,
    int promptTokens,
    int completionTokens,
    BigDecimal costUsd,
    BigDecimal costPerCaseUsd,
    Map<String, Double> rootCauseAccuracyByTag
) {

    public static EvalSummary of(List<EvalCaseResult> results, Properties pricing) {
        int n = results.size();
        double jobs = share(results, EvalCaseResult::jobSucceeded);
        double root = share(results, EvalCaseResult::rootCauseCorrect);
        double route = share(results, EvalCaseResult::routeCorrect);

        // An UNKNOWN answer cites nothing by design (the verifier rejects it, the gate sends it to a person), so
        // citation validity is judged only on answers that actually claim a cause.
        List<EvalCaseResult> claimsCause = results.stream()
            .filter(r -> r.jobSucceeded() && !"UNKNOWN".equals(r.rootCause())).toList();
        double citeValid = claimsCause.isEmpty() ? 1.0 : share(claimsCause, r -> Boolean.TRUE.equals(r.citationsValid()));
        // A case that expects "no answer" has nothing to cite, so it does not count against recall.
        List<EvalCaseResult> needCitation = results.stream().filter(r -> !r.expectedRootCause().equals("UNKNOWN")).toList();
        double citeRecall = needCitation.isEmpty() ? 1.0 : share(needCitation, EvalCaseResult::citationsComplete);

        List<Long> wall = results.stream().map(EvalCaseResult::wallMs).sorted().toList();
        int prompt = results.stream().mapToInt(EvalCaseResult::promptTokens).sum();
        int completion = results.stream().mapToInt(EvalCaseResult::completionTokens).sum();
        BigDecimal cost = results.stream().map(r -> cost(r, pricing)).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Double> byTag = new TreeMap<>();
        results.stream().flatMap(r -> r.tags().stream()).distinct().forEach(tag ->
            byTag.put(tag, share(results.stream().filter(r -> r.tags().contains(tag)).toList(), EvalCaseResult::rootCauseCorrect)));

        return new EvalSummary(n, jobs, root, route, citeValid, citeRecall,
            percentile(wall, 50), percentile(wall, 95), prompt, completion,
            cost.setScale(6, RoundingMode.HALF_UP),
            n == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP),
            byTag);
    }

    private static BigDecimal cost(EvalCaseResult r, Properties pricing) {
        if (r.model() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal in = new BigDecimal(pricing.getProperty(r.model() + ".input", "0"));
        BigDecimal out = new BigDecimal(pricing.getProperty(r.model() + ".output", "0"));
        return in.multiply(BigDecimal.valueOf(r.promptTokens()))
            .add(out.multiply(BigDecimal.valueOf(r.completionTokens())))
            .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }

    private static double share(List<EvalCaseResult> rows, Predicate<EvalCaseResult> test) {
        return rows.isEmpty() ? 0 : (double) rows.stream().filter(test).count() / rows.size();
    }

    /** Nearest-rank percentile; fine for a few dozen cases. */
    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
