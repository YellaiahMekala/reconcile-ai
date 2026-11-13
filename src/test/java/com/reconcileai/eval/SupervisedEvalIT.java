package com.reconcileai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supervised modes on the scripted model: both variants on the 28 easy cases (held to the same floor as the other
 * modes) and all four modes on the 12 hard cases, written as one comparison table. The scripted model answers with
 * keyword rules, so this guards the plumbing and the decision-rule arithmetic, not model quality; the real comparison
 * is {@link OpenAiModeComparisonIT}.
 */
class SupervisedEvalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void supervisedModesKeepTheFloorAndTheComparisonIsWritten() throws IOException {
        List<ModeComparison.Row> rows = ModeComparison.run(adminJdbc(), "mock", "Chat model: scripted stand-in. Embeddings: "
            + "test hashing model. Pipeline check only; says nothing about real model quality.", ModeComparison.ALL_MODES, false);
        List<ModeComparison.Row> easy = List.of(
            row(EvalHarness.runAndReport(adminJdbc(), "mock-supervised-easy", "scripted", "SUPERVISED", new EvalCaseLoader().loadAll()), "SUPERVISED"),
            row(EvalHarness.runAndReport(adminJdbc(), "mock-supervised-planned-easy", "scripted", "SUPERVISED_PLANNED",
                new EvalCaseLoader().loadAll()), "SUPERVISED_PLANNED"));
        ModeComparison.write("mock", "Scripted models, offline. Hard set only for all modes; easy set for the supervised modes.",
            concat(rows, easy));

        JsonNode floor;
        try (InputStream in = SupervisedEvalIT.class.getResourceAsStream("/eval/thresholds-mock.json")) {
            floor = new ObjectMapper().readTree(in);
        }
        for (ModeComparison.Row r : easy) {
            EvalSummary s = r.summary();
            assertThat(s.jobSuccessRate()).as(r.mode()).isGreaterThanOrEqualTo(floor.get("minJobSuccessRate").asDouble());
            assertThat(s.rootCauseAccuracy()).as(r.mode()).isGreaterThanOrEqualTo(floor.get("minRootCauseAccuracy").asDouble());
            assertThat(s.routeAccuracy()).as(r.mode()).isGreaterThanOrEqualTo(floor.get("minRouteAccuracy").asDouble());
            assertThat(s.citationValidRate()).as(r.mode()).isGreaterThanOrEqualTo(floor.get("minCitationValidRate").asDouble());
        }
        for (ModeComparison.Row r : concat(rows, easy)) {
            assertThat(r.results()).as(r.mode() + " " + r.set()).allMatch(EvalCaseResult::jobSucceeded);
            r.results().forEach(c -> assertThat(c.route()).isNotEqualTo("AUTO_RESOLVE"));
            if (r.mode().startsWith("SUPERVISED")) {
                assertThat(r.results()).allMatch(c -> c.reviewVerdict() != null);
                // the reviewer can only add manual reviews: a challenged case never reaches an approver
                assertThat(r.results()).filteredOn(c -> "CHALLENGE".equals(c.reviewVerdict()))
                    .allMatch(c -> "MANUAL_REVIEW".equals(c.route()));
            }
        }
        // the rule is evaluated for both variants (the scripted model costs nothing, so cost passes trivially)
        assertThat(ModeComparison.decide(rows)).extracting(ModeComparison.Decision::mode)
            .containsExactly("SUPERVISED", "SUPERVISED_PLANNED");
    }

    private static ModeComparison.Row row(EvalHarness.Outcome o, String mode) {
        return new ModeComparison.Row(mode, "easy", o.summary(), o.results());
    }

    private static List<ModeComparison.Row> concat(List<ModeComparison.Row> a, List<ModeComparison.Row> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
