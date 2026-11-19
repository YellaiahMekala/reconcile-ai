// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Platform Q3.7: per-case rows of one engine run (CSV) and the paired SINGLE_CALL vs RERO comparison under
 * docs/adr/RERO_EVALUATION_RULE.md section 3 (criteria C2 to C6 and the C1 policy part; the C1 safety-IT part is a
 * separate build run and is passed in). Writes markdown; decides nothing beyond "all criteria pass" (KEEP allowed) or
 * not (CUT, the default).
 */
final class ReroKeepCutReport {

    /** One case of one engine run. costUsd is the gateway ledger's actual_cost_usd summed over the job's rows. */
    record Row(String caseId, String engine, String jobId, boolean succeeded, String failureReason,
               String expectedCause, String cause, String expectedRoute, String route, Boolean citationsValid,
               int requiredCitations, int missingCitations, long latencyMs, long wallMs, BigDecimal costUsd,
               int ledgerRows, String ledgerStatuses, int toolCallsUsed, Integer toolCallsMax, boolean outcomeUnknown,
               String reroRunId) {

        boolean causeCorrect() {
            return succeeded && Objects.equals(expectedCause, cause);
        }

        /** A wrong cause that went to an approver (any route but MANUAL_REVIEW). */
        boolean wrongCauseToApprover() {
            return succeeded && !causeCorrect() && !"MANUAL_REVIEW".equals(route);
        }

        /** C3: a cause-claiming answer (not UNKNOWN) whose citations did not verify. */
        boolean claimWithUnverifiedCitation() {
            return succeeded && !"UNKNOWN".equals(cause) && Boolean.FALSE.equals(citationsValid);
        }
    }

    static final String HEADER = "caseId,engine,jobId,succeeded,failureReason,expectedCause,cause,expectedRoute,route,"
        + "citationsValid,requiredCitations,missingCitations,latencyMs,wallMs,costUsd,ledgerRows,ledgerStatuses,"
        + "toolCallsUsed,toolCallsMax,outcomeUnknown,reroRunId";

    private ReroKeepCutReport() {
    }

    static void writeCsv(Path file, List<Row> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Row r : rows) {
            lines.add(String.join(",", r.caseId(), r.engine(), s(r.jobId()), String.valueOf(r.succeeded()),
                s(r.failureReason()), s(r.expectedCause()), s(r.cause()), s(r.expectedRoute()), s(r.route()),
                s(r.citationsValid()), String.valueOf(r.requiredCitations()), String.valueOf(r.missingCitations()),
                String.valueOf(r.latencyMs()), String.valueOf(r.wallMs()), r.costUsd().toPlainString(),
                String.valueOf(r.ledgerRows()), s(r.ledgerStatuses()), String.valueOf(r.toolCallsUsed()),
                s(r.toolCallsMax()), String.valueOf(r.outcomeUnknown()), s(r.reroRunId())));
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Row> readCsv(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Row> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split(",", -1);
                rows.add(new Row(f[0], f[1], n(f[2]), Boolean.parseBoolean(f[3]), n(f[4]), n(f[5]), n(f[6]), n(f[7]),
                    n(f[8]), f[9].isEmpty() ? null : Boolean.valueOf(f[9]), Integer.parseInt(f[10]),
                    Integer.parseInt(f[11]), Long.parseLong(f[12]), Long.parseLong(f[13]), new BigDecimal(f[14]),
                    Integer.parseInt(f[15]), n(f[16]), Integer.parseInt(f[17]),
                    f[18].isEmpty() ? null : Integer.valueOf(f[18]), Boolean.parseBoolean(f[19]), n(f[20])));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Result of one criterion. */
    record Criterion(String id, boolean pass, String detail) { }

    record Comparison(List<Criterion> criteria, String markdown) {
        boolean allPass() {
            return criteria.stream().allMatch(Criterion::pass);
        }
    }

    /**
     * @param safetyItsPassed C1 safety-IT part, from the separate build run with RERO enabled (null = not run, fails)
     * @param duplicateRuns   C6: keys with more than one run (RERO store and ledger), counted by the caller
     */
    static Comparison compare(List<Row> baseline, List<Row> rero, Boolean safetyItsPassed, int duplicateRuns) {
        Map<String, Row> b = byCase(baseline);
        Map<String, Row> r = byCase(rero);
        if (!b.keySet().equals(r.keySet())) {
            throw new IllegalArgumentException("engines must cover the same cases");
        }
        int n = b.size();
        List<Criterion> criteria = new ArrayList<>();
        StringBuilder md = new StringBuilder();

        // C1 policy part
        long autoResolve = rero.stream().filter(x -> "AUTO_RESOLVE".equals(x.route())).count()
            + baseline.stream().filter(x -> "AUTO_RESOLVE".equals(x.route())).count();
        List<String> newWrongToApprover = new ArrayList<>();
        for (String id : b.keySet()) {
            if (r.get(id).wrongCauseToApprover() && "MANUAL_REVIEW".equals(b.get(id).route())) {
                newWrongToApprover.add(id);
            }
        }
        boolean c1 = autoResolve == 0 && newWrongToApprover.isEmpty() && Boolean.TRUE.equals(safetyItsPassed);
        criteria.add(new Criterion("C1", c1, "AUTO_RESOLVE routes: " + autoResolve
            + "; RERO wrong cause to approver where baseline went to manual review: " + newWrongToApprover.size()
            + (newWrongToApprover.isEmpty() ? "" : " " + newWrongToApprover)
            + "; safety ITs with RERO enabled: " + (safetyItsPassed == null ? "not run" : safetyItsPassed ? "pass" : "FAIL")));

        // C2 root cause, paired
        int baseRight = 0;
        int reroRight = 0;
        int baseOnly = 0;
        int reroOnly = 0;
        for (String id : b.keySet()) {
            boolean x = b.get(id).causeCorrect();
            boolean y = r.get(id).causeCorrect();
            baseRight += x ? 1 : 0;
            reroRight += y ? 1 : 0;
            baseOnly += x && !y ? 1 : 0;
            reroOnly += y && !x ? 1 : 0;
        }
        double p = ReroKeepCutStats.mcnemarExact(baseOnly, reroOnly);
        boolean c2 = baseOnly <= 1 && reroRight >= baseRight - 1;
        criteria.add(new Criterion("C2", c2, String.format(Locale.ROOT,
            "baseline %d/%d (%s), RERO %d/%d (%s); right in baseline only: %d, right in RERO only: %d; "
                + "exact McNemar p = %.4f", baseRight, n, ReroKeepCutStats.wilson(baseRight, n).percent(), reroRight, n,
            ReroKeepCutStats.wilson(reroRight, n).percent(), baseOnly, reroOnly, p)));

        // C3 citations
        long baseUnverified = baseline.stream().filter(Row::claimWithUnverifiedCitation).count();
        long reroUnverified = rero.stream().filter(Row::claimWithUnverifiedCitation).count();
        int required = baseline.stream().mapToInt(Row::requiredCitations).sum();
        int baseFound = required - baseline.stream().mapToInt(Row::missingCitations).sum();
        int reroFound = rero.stream().mapToInt(Row::requiredCitations).sum() - rero.stream().mapToInt(Row::missingCitations).sum();
        boolean c3 = reroUnverified <= baseUnverified && reroFound >= baseFound;
        criteria.add(new Criterion("C3", c3, String.format(Locale.ROOT,
            "cause-claiming answers with an unverified citation: baseline %d, RERO %d; required-evidence recall: "
                + "baseline %d/%d (%s), RERO %d/%d (%s)", baseUnverified, reroUnverified, baseFound, required,
            ReroKeepCutStats.wilson(baseFound, required).percent(), reroFound, required,
            ReroKeepCutStats.wilson(reroFound, required).percent())));

        // C4 cost from the ledger
        Cost bc = Cost.of(baseline);
        Cost rc = Cost.of(rero);
        BigDecimal ratio = bc.mean().signum() == 0 ? null : rc.mean().divide(bc.mean(), 2, RoundingMode.HALF_UP);
        boolean c4 = ratio != null && ratio.compareTo(BigDecimal.valueOf(5)) <= 0;
        criteria.add(new Criterion("C4", c4, "mean cost per case (gateway ledger actual_cost_usd): baseline " + bc
            + ", RERO " + rc + "; ratio " + (ratio == null ? "n/a (baseline cost 0)" : ratio + "x") + " (limit 5x)"));

        // C5 latency
        long bp95 = ReroKeepCutStats.nearestRank(baseline.stream().map(Row::latencyMs).toList(), 95);
        long rp95 = ReroKeepCutStats.nearestRank(rero.stream().map(Row::latencyMs).toList(), 95);
        boolean c5 = rp95 <= 4 * bp95 && rp95 <= 15_000;
        criteria.add(new Criterion("C5", c5, String.format(Locale.ROOT,
            "p95 job start to terminal (nearest rank, n=%d): baseline %d ms, RERO %d ms; ratio %.2fx (limit 4x) and "
                + "absolute limit 15000 ms", n, bp95, rp95, bp95 == 0 ? Double.NaN : (double) rp95 / bp95)));

        // C6 budget and idempotency
        long overBudget = rero.stream().filter(x -> x.toolCallsMax() != null && x.toolCallsUsed() > x.toolCallsMax()
            || "TOOL_BUDGET_EXCEEDED".equals(x.failureReason())).count();
        long unknown = rero.stream().filter(Row::outcomeUnknown).count()
            + baseline.stream().filter(Row::outcomeUnknown).count();
        boolean c6 = overBudget == 0 && duplicateRuns == 0 && unknown == 0;
        criteria.add(new Criterion("C6", c6, "runs over tool budget: " + overBudget + "; duplicate runs for one key: "
            + duplicateRuns + "; unknown outcomes left: " + unknown));

        md.append("| # | Result | Detail |\n|---|---|---|\n");
        for (Criterion c : criteria) {
            md.append("| ").append(c.id()).append(" | ").append(c.pass() ? "PASS" : "**FAIL**").append(" | ")
                .append(c.detail()).append(" |\n");
        }
        md.append("\n| Case | Expected | Baseline cause / route | RERO cause / route | RERO failure | Base ms | RERO ms "
            + "| Base USD | RERO USD | RERO tools |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (String id : b.keySet()) {
            Row x = b.get(id);
            Row y = r.get(id);
            md.append("| ").append(id).append(" | ").append(x.expectedCause()).append(" | ")
                .append(cell(x)).append(" | ").append(cell(y)).append(" | ").append(s(y.failureReason()))
                .append(" | ").append(x.latencyMs()).append(" | ").append(y.latencyMs()).append(" | ")
                .append(x.costUsd().toPlainString()).append(" | ").append(y.costUsd().toPlainString()).append(" | ")
                .append(y.toolCallsUsed()).append("/").append(s(y.toolCallsMax())).append(" |\n");
        }
        md.append("\nDecision under the rule: ").append(criteria.stream().allMatch(Criterion::pass)
            ? "all criteria pass, KEEP allowed" : "at least one criterion fails, CUT").append('\n');
        return new Comparison(criteria, md.toString());
    }

    record Cost(BigDecimal mean, BigDecimal min, BigDecimal max, BigDecimal total) {
        static Cost of(List<Row> rows) {
            BigDecimal total = rows.stream().map(Row::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal min = rows.stream().map(Row::costUsd).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal max = rows.stream().map(Row::costUsd).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal mean = rows.isEmpty() ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(rows.size()), 8, RoundingMode.HALF_UP);
            return new Cost(mean, min, max, total);
        }

        @Override
        public String toString() {
            return mean.stripTrailingZeros().toPlainString() + " (min " + min.stripTrailingZeros().toPlainString()
                + ", max " + max.stripTrailingZeros().toPlainString() + ", total "
                + total.stripTrailingZeros().toPlainString() + ")";
        }
    }

    private static Map<String, Row> byCase(List<Row> rows) {
        Map<String, Row> m = new LinkedHashMap<>();
        rows.forEach(x -> m.put(x.caseId(), x));
        return m;
    }

    private static String cell(Row x) {
        return x.succeeded() ? x.cause() + (x.causeCorrect() ? "" : " (wrong)") + " / " + x.route() : "FAILED";
    }

    private static String s(Object v) {
        return v == null ? "" : v.toString().replace(',', ';').replace('\n', ' ');
    }

    private static String n(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
