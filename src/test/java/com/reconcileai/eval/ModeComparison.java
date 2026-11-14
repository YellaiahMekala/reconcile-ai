package com.reconcileai.eval;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the same labelled cases through several investigation modes and writes one comparison table, plus the
 * decision rule for the supervised modes, which was fixed before any run (ADR-supervisor-agents section 5):
 *
 * <blockquote>A supervised variant is worth recommending only if, on the HARD set, it at least halves the number of
 * wrong-cause answers that reach an approver compared with single-call, and its cost per case over all cases is at
 * most 5x single-call.</blockquote>
 *
 * Every mode runs under its own fresh tenant, so the "similar resolved breaks" tool never sees another run's data.
 */
final class ModeComparison {

    static final List<String> ALL_MODES = List.of("SINGLE_CALL", "AGENT", "SUPERVISED", "SUPERVISED_PLANNED");

    /** One mode on one case set. */
    record Row(String mode, String set, EvalSummary summary, List<EvalCaseResult> results) {

        long wrongToApprover() {
            return results.stream().filter(EvalCaseResult::wrongCauseReachedApprover).count();
        }

        long reviewerChallenges() {
            return results.stream().filter(r -> "CHALLENGE".equals(r.reviewVerdict())).count();
        }

        double modelCallsPerCase() {
            return results.isEmpty() ? 0 : results.stream().mapToInt(EvalCaseResult::modelCalls).average().orElse(0);
        }
    }

    record Decision(String mode, boolean recommended, String why) {}

    private ModeComparison() {}

    static List<Row> run(JdbcTemplate admin, String label, String notes, List<String> modes, boolean includeEasySet) {
        return run(admin, label, notes, modes, includeEasySet, () -> { });
    }

    /** beforePhase runs before every mode/set phase (live runs reset the circuit breaker so one bad phase cannot fail the next). */
    static List<Row> run(JdbcTemplate admin, String label, String notes, List<String> modes, boolean includeEasySet,
                         Runnable beforePhase) {
        EvalCaseLoader loader = new EvalCaseLoader();
        List<EvalCase> easy = includeEasySet ? loader.loadAll() : List.of();
        List<EvalCase> hard = loader.loadHard();
        List<Row> rows = new ArrayList<>();
        for (String mode : modes) {
            String m = mode.toLowerCase(Locale.ROOT).replace('_', '-');
            if (!easy.isEmpty()) {
                beforePhase.run();
                EvalHarness.Outcome o = EvalHarness.runAndReport(admin, label + "-" + m + "-easy", notes, mode, easy);
                rows.add(new Row(mode, "easy", o.summary(), o.results()));
            }
            beforePhase.run();
            EvalHarness.Outcome o = EvalHarness.runAndReport(admin, label + "-" + m + "-hard", notes, mode, hard);
            rows.add(new Row(mode, "hard", o.summary(), o.results()));
        }
        return rows;
    }

    /** Applies the pre-registered rule. Cost is per case over every case each mode ran. */
    static List<Decision> decide(List<Row> rows) {
        Row singleHard = find(rows, "SINGLE_CALL", "hard");
        if (singleHard == null) {
            return List.of();
        }
        BigDecimal singleCost = costPerCase(rows, "SINGLE_CALL");
        List<Decision> out = new ArrayList<>();
        for (String mode : List.of("SUPERVISED", "SUPERVISED_PLANNED")) {
            Row hard = find(rows, mode, "hard");
            if (hard == null) {
                continue;
            }
            long base = singleHard.wrongToApprover();
            long now = hard.wrongToApprover();
            BigDecimal cost = costPerCase(rows, mode);
            boolean halved = base > 0 && now * 2 <= base;
            boolean affordable = singleCost.signum() == 0 ? cost.signum() == 0
                : cost.compareTo(singleCost.multiply(BigDecimal.valueOf(5))) <= 0;
            String ratio = singleCost.signum() == 0 ? "n/a (zero-cost model)"
                : cost.divide(singleCost, 1, RoundingMode.HALF_UP) + "x";
            String why = "hard-set wrong causes reaching an approver: " + now + " vs " + base + " for single-call"
                + (base == 0 ? " (single-call had none, so there is nothing to halve)" : "")
                + "; cost per case " + ratio + " of single-call";
            out.add(new Decision(mode, halved && affordable, why));
        }
        return out;
    }

    static Path write(String label, String notes, List<Row> rows) {
        StringBuilder md = new StringBuilder("# Investigation mode comparison: ").append(label).append("\n\n")
            .append(notes).append("\n\n")
            .append("| Mode | Set | Cases | Jobs ok | Root cause | Route | Wrong cause to approver | Reviewer challenges | "
                + "Citations valid | Model calls/case | p50 ms | p95 ms | Cost/case USD |\n")
            .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            EvalSummary s = r.summary();
            md.append("| ").append(r.mode()).append(" | ").append(r.set()).append(" | ").append(s.cases())
                .append(" | ").append(pct(s.jobSuccessRate())).append(" | ").append(pct(s.rootCauseAccuracy()))
                .append(" | ").append(pct(s.routeAccuracy())).append(" | ").append(r.wrongToApprover())
                .append(" | ").append(r.mode().startsWith("SUPERVISED") ? String.valueOf(r.reviewerChallenges()) : "-")
                .append(" | ").append(pct(s.citationValidRate()))
                .append(" | ").append(String.format(Locale.ROOT, "%.1f", r.modelCallsPerCase()))
                .append(" | ").append(s.p50WallMs()).append(" | ").append(s.p95WallMs())
                .append(" | ").append(s.costPerCaseUsd().toPlainString()).append(" |\n");
        }
        md.append("\n## Decision rule (fixed before the run)\n\n")
            .append("Recommend a supervised variant only if, on the hard set, it at least halves the wrong-cause answers that ")
            .append("reach an approver compared with single-call, at no more than 5x the single-call cost per case.\n\n");
        for (Decision d : decide(rows)) {
            md.append("- **").append(d.mode()).append("**: ").append(d.recommended() ? "meets the rule" : "does not meet the rule")
                .append(" (").append(d.why()).append(")\n");
        }
        md.append("\n## Per-case results on the hard set\n\n| Case | Tags | Expected | ");
        List<Row> hard = rows.stream().filter(r -> r.set().equals("hard")).toList();
        hard.forEach(r -> md.append(r.mode()).append(" | "));
        md.append("\n|---|---|---|");
        hard.forEach(r -> md.append("---|"));
        md.append('\n');
        if (!hard.isEmpty()) {
            for (int i = 0; i < hard.get(0).results().size(); i++) {
                EvalCaseResult first = hard.get(0).results().get(i);
                md.append("| ").append(first.caseId()).append(" | ").append(String.join(", ", first.tags())).append(" | ")
                    .append(first.expectedRootCause()).append(" / ").append(first.expectedRoute()).append(" | ");
                for (Row r : hard) {
                    EvalCaseResult c = r.results().get(i);
                    md.append(c.jobSucceeded() ? c.rootCause() + " / " + c.route() : "FAILED " + c.failureReason())
                        .append(c.reviewVerdict() == null ? "" : " (" + c.reviewVerdict() + ")").append(" | ");
                }
                md.append('\n');
            }
        }
        Path file = EvalHarness.REPORT_DIR.resolve(label + "-mode-comparison.md");
        try {
            Files.createDirectories(EvalHarness.REPORT_DIR);
            Files.writeString(file, md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("[eval] mode comparison written to " + file.toAbsolutePath());
        return file;
    }

    private static Row find(List<Row> rows, String mode, String set) {
        return rows.stream().filter(r -> r.mode().equals(mode) && r.set().equals(set)).findFirst().orElse(null);
    }

    private static BigDecimal costPerCase(List<Row> rows, String mode) {
        List<Row> own = rows.stream().filter(r -> r.mode().equals(mode)).toList();
        int cases = own.stream().mapToInt(r -> r.summary().cases()).sum();
        BigDecimal total = own.stream().map(r -> r.summary().costUsd()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return cases == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(cases), 8, RoundingMode.HALF_UP);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
