package com.reconcileai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes report-LABEL.json (for scripts) and report-LABEL.md (for people) into one directory. */
public final class EvalReportWriter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Path[] write(Path dir, String label, String notes, EvalSummary summary, List<EvalCaseResult> results,
                        HumanScores human) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("label", label);
        json.put("generatedAt", Instant.now().toString());
        json.put("notes", notes);
        json.put("summary", summary);
        json.put("humanScores", human);
        json.put("cases", results);
        try {
            Files.createDirectories(dir);
            Path jsonFile = dir.resolve("report-" + label + ".json");
            Path mdFile = dir.resolve("report-" + label + ".md");
            Files.writeString(jsonFile, mapper.writeValueAsString(json), StandardCharsets.UTF_8);
            Files.writeString(mdFile, markdown(label, notes, summary, results, human), StandardCharsets.UTF_8);
            return new Path[] {jsonFile, mdFile};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String markdown(String label, String notes, EvalSummary s, List<EvalCaseResult> results, HumanScores human) {
        StringBuilder md = new StringBuilder();
        md.append("# Investigation eval: ").append(label).append("\n\n").append(notes).append("\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Cases | ").append(s.cases()).append(" |\n");
        md.append("| Jobs that finished with a proposal | ").append(pct(s.jobSuccessRate())).append(" |\n");
        md.append("| Root-cause accuracy | ").append(pct(s.rootCauseAccuracy())).append(" |\n");
        md.append("| Route accuracy | ").append(pct(s.routeAccuracy())).append(" |\n");
        md.append("| Cause-claiming answers whose citations verified | ").append(pct(s.citationValidRate())).append(" |\n");
        md.append("| Required evidence cited (recall) | ").append(pct(s.citationRecall())).append(" |\n");
        md.append("| Latency p50 / p95 | ").append(s.p50WallMs()).append(" ms / ").append(s.p95WallMs()).append(" ms |\n");
        md.append("| Tokens (prompt + completion) | ").append(s.promptTokens()).append(" + ").append(s.completionTokens()).append(" |\n");
        md.append("| Cost | $").append(s.costUsd().toPlainString()).append(" total, $")
            .append(s.costPerCaseUsd().toPlainString()).append(" per case |\n\n");

        md.append("## Root-cause accuracy by tag\n\n| Tag | Accuracy |\n|---|---|\n");
        s.rootCauseAccuracyByTag().forEach((tag, acc) ->
            md.append("| ").append(tag).append(" | ").append(pct(acc)).append(" |\n"));

        if (human.scoredCases() > 0) {
            md.append("\n## Human scores (1 to 5, ").append(human.scoredCases()).append(" cases scored)\n\n")
                .append("| Explanation clarity | Faithfulness to evidence |\n|---|---|\n| ")
                .append(String.format("%.2f", human.avgClarity())).append(" | ")
                .append(String.format("%.2f", human.avgFaithfulness())).append(" |\n");
        }

        md.append("\n## Cases\n\n| Case | Expected | Got | Route (expected / got) | Citations | Result |\n|---|---|---|---|---|---|\n");
        for (EvalCaseResult r : results) {
            md.append("| ").append(r.caseId()).append(" | ").append(r.expectedRootCause()).append(" | ")
                .append(r.jobSucceeded() ? r.rootCause() : "no proposal").append(" | ")
                .append(r.expectedRoute()).append(" / ").append(r.jobSucceeded() ? r.route() : "-").append(" | ")
                .append(!r.jobSucceeded() || "UNKNOWN".equals(r.rootCause()) ? "n/a" : (Boolean.TRUE.equals(r.citationsValid()) ? "valid" : "invalid")).append(" | ")
                .append(r.rootCauseCorrect() && r.routeCorrect() ? "pass" : "miss").append(" |\n");
        }

        List<EvalCaseResult> misses = results.stream().filter(r -> !(r.rootCauseCorrect() && r.routeCorrect())).toList();
        md.append("\n## Misses\n\n");
        if (misses.isEmpty()) {
            md.append("None.\n");
        }
        for (EvalCaseResult r : misses) {
            md.append("- **").append(r.caseId()).append("**: ");
            if (!r.jobSucceeded()) {
                md.append("job failed (").append(r.failureReason()).append(")\n");
            } else {
                md.append("root cause ").append(r.rootCause()).append(" (expected ").append(r.expectedRootCause())
                    .append("), route ").append(r.route()).append(" because ").append(r.routeReason());
                if (!r.missingRequiredCitations().isEmpty()) {
                    md.append(", did not cite ").append(r.missingRequiredCitations());
                }
                md.append("\n");
            }
        }
        return md.toString();
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}
