// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import com.reconcileai.support.JwtTestSupport;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Shared by the mock and the live eval tests: run every case, summarise, write the report, print where it went. */
final class EvalHarness {

    static final Path REPORT_DIR = Path.of("target", "eval");
    static final Path HUMAN_SCORES = Path.of("eval", "human-scores.csv");

    record Outcome(List<EvalCaseResult> results, EvalSummary summary) {}

    private EvalHarness() {}

    static Outcome runAndReport(JdbcTemplate admin, String label, String notes) {
        return runAndReport(admin, label, notes, false);
    }

    static Outcome runAndReport(JdbcTemplate admin, String label, String notes, boolean agentMode) {
        return runAndReport(admin, label, notes, agentMode ? "AGENT" : "SINGLE_CALL", new EvalCaseLoader().loadAll());
    }

    /** Runs the given cases in one mode and writes the usual report under target/eval. */
    static Outcome runAndReport(JdbcTemplate admin, String label, String notes, String mode, List<EvalCase> cases) {
        return runAndReport(admin, label, notes, mode, cases, "TENANT_EVAL_" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Same, for a fixed tenant (the integrated platform run needs the tenant that has gateway and EKA clients). */
    static Outcome runAndReport(JdbcTemplate admin, String label, String notes, String mode, List<EvalCase> cases,
                                String tenant) {
        String bearer = JwtTestSupport.bearer("eval-analyst", tenant, List.of("RECON_ANALYST"));

        List<EvalCaseResult> results = new EvalRunner(bearer, admin, mode).runAll(cases);
        EvalSummary summary = EvalSummary.of(results, pricing());
        Path[] files = new EvalReportWriter().write(REPORT_DIR, label, notes, summary, results, HumanScores.load(HUMAN_SCORES));
        System.out.println("[eval] " + label + ": root-cause " + summary.rootCauseAccuracy() + ", route " + summary.routeAccuracy()
            + ", report " + files[1].toAbsolutePath());
        return new Outcome(results, summary);
    }

    static Properties pricing() {
        Properties p = new Properties();
        try (InputStream in = EvalHarness.class.getResourceAsStream("/eval/pricing.properties")) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
