// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Pushes each case through the public API exactly as an analyst would (create break, upload evidence, start the
 * investigation, wait), then reads the proposal and the llm_call rows. Nothing is stubbed except the chat model the
 * running profile selects, so retrieval, prompt, verifier and policy gate are all measured.
 */
public final class EvalRunner {

    private final String bearer;
    private final JdbcTemplate admin;
    private final String mode;

    public EvalRunner(String bearer, JdbcTemplate admin) {
        this(bearer, admin, "SINGLE_CALL");
    }

    /** @param agentMode true to start every investigation in AGENT mode instead of the single-call mode */
    public EvalRunner(String bearer, JdbcTemplate admin, boolean agentMode) {
        this(bearer, admin, agentMode ? "AGENT" : "SINGLE_CALL");
    }

    /** @param mode SINGLE_CALL, AGENT, SUPERVISED or SUPERVISED_PLANNED */
    public EvalRunner(String bearer, JdbcTemplate admin, String mode) {
        this.bearer = bearer;
        this.admin = admin;
        this.mode = mode;
    }

    public List<EvalCaseResult> runAll(List<EvalCase> cases) {
        List<EvalCaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            results.add(run(c));
        }
        return results;
    }

    public EvalCaseResult run(EvalCase c) {
        String breakId = given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"EVAL-%s-%s","ledgerAmount":"%s","externalAmount":"%s","currency":"%s"}
                """.formatted(c.id(), UUID.randomUUID(), c.ledgerAmount(), c.externalAmount(), c.currency()))
            .when().post("/api/v1/breaks").then().statusCode(201).extract().path("id");
        for (String file : c.evidence()) {
            ApiTestSupport.upload(bearer, breakId, EvalCaseLoader.EVIDENCE_DIR + file, "text/plain");
        }

        long started = System.nanoTime();
        String jobStarted = "SINGLE_CALL".equals(mode) ? InvestigationTestSupport.start(bearer, breakId, c.analystNote())
            : InvestigationTestSupport.startMode(bearer, breakId, mode, c.analystNote());
        // Longer than any mode's own timeout (supervised: 180 s), so a slow job is recorded as FAILED, not a harness crash.
        JsonPath job = InvestigationTestSupport.awaitTerminal(bearer, jobStarted, java.time.Duration.ofSeconds(240));
        long wallMs = (System.nanoTime() - started) / 1_000_000;

        String jobId = job.getString("id");
        Map<String, Object> usage = admin.queryForMap("""
            SELECT coalesce(sum(prompt_tokens), 0) AS p, coalesce(sum(completion_tokens), 0) AS c,
                   coalesce(sum(latency_ms), 0) AS l, max(model) AS model, count(*) AS n
              FROM llm_call WHERE job_id = ?::uuid""", jobId);
        int prompt = ((Number) usage.get("p")).intValue();
        int completion = ((Number) usage.get("c")).intValue();
        long llmMs = ((Number) usage.get("l")).longValue();
        String model = (String) usage.get("model");
        int calls = ((Number) usage.get("n")).intValue();
        String verdict = job.getString("reviewVerdict");

        if (!"SUCCEEDED".equals(job.getString("status"))) {
            return new EvalCaseResult(c.id(), c.tags(), c.expected().rootCause(), c.expected().route(), false,
                job.getString("failureReason"), null, null, null, null, List.of(), c.expected().mustCiteEvidence(),
                prompt, completion, llmMs, wallMs, model, toolTrace(jobId), calls, verdict);
        }

        JsonPath p = InvestigationTestSupport.proposal(bearer, job.getString("proposalId"));
        List<String> citedFiles = citedFiles(admin, p.getList("citedChunkIds", String.class));
        List<String> missing = c.expected().mustCiteEvidence().stream().filter(f -> !citedFiles.contains(f)).toList();

        return new EvalCaseResult(c.id(), c.tags(), c.expected().rootCause(), c.expected().route(), true, null,
            p.getString("rootCause"), p.getString("route"), p.getString("routeReason"), p.getBoolean("citationsValid"),
            citedFiles, missing, prompt, completion, llmMs, wallMs, model, toolTrace(jobId), calls, verdict);
    }

    /**
     * The distinct evidence file names behind the cited chunk ids, in citation order. Compared as text on purpose:
     * a model can cite a malformed id (live run 2026-11-13: a UUID with a dropped digit). That is an invalid citation
     * and scores as "not cited"; casting it to uuid in SQL crashed the whole comparison instead.
     */
    static List<String> citedFiles(JdbcTemplate admin, List<String> chunkIds) {
        List<String> files = new ArrayList<>();
        for (String chunkId : chunkIds == null ? List.<String>of() : chunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            admin.queryForList("SELECT metadata->>'fileName' FROM vector_store WHERE id::text = ?", String.class, chunkId)
                .stream().filter(f -> f != null && !files.contains(f)).forEach(files::add);
        }
        return files;
    }

    /** One short line per run, e.g. getBreakSummary:OK:120 > searchEvidence:OK:43; null in single-call mode. */
    private String toolTrace(String jobId) {
        if ("SINGLE_CALL".equals(mode)) {
            return null;
        }
        return String.join(" > ", admin.queryForList(
            "SELECT tool || ':' || outcome || ':' || result_chars FROM agent_tool_call WHERE job_id = ?::uuid ORDER BY seq",
            String.class, jobId));
    }
}
