package com.reconcileai.eval;

import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q3.7: the RERO keep/cut run under docs/adr/RERO_EVALUATION_RULE.md. PAID; tag {@code platform-live},
 * excluded from the default build, gated by {@code GATEWAY_LIVE_APPROVED=yes} and {@code RERO_LIVE_BASE_URL}.
 * <p>
 * One application context for both engines, so the policy gate, retrieval and the (test hashing) embeddings are
 * identical. Both engines reach the model only through the real ai-gateway (gpt-4o-mini, temperature 0.0 sent by
 * both callers). Knowledge is OFF for both: the SINGLE_CALL pre-step is disabled and RERO's knowledge tool answers
 * UNAVAILABLE. The app listens on a fixed port ({@code RERO_EVAL_APP_PORT}, default 8087) because the real RERO
 * process calls its internal API there.
 * <p>
 * Environment: {@code KC_SVC_TENANT_A_SECRET} (platform realm), {@code RERO_RUN_TOKEN_PRIVATE_KEY_PEM} /
 * {@code RERO_RUN_TOKEN_PUBLIC_KEY_PEM} (throwaway P-256 pair, never committed), optional {@code RERO_EVAL_ENGINES}
 * (default {@code SINGLE_CALL,RERO}), {@code RERO_EVAL_SET} ({@code primary} or {@code hard}),
 * {@code RERO_EVAL_BASELINE_CSV} (compare a repeated RERO run with an earlier baseline CSV),
 * {@code RERO_EVAL_SAFETY_ITS} ({@code pass}/{@code fail}, from the separate RERO-enabled safety-IT run),
 * {@code RERO_DB_PATH} (RERO's SQLite file, to count runs per key). Per-case cost is read from the gateway ledger
 * with {@code docker exec platform-gw-postgres-1 psql}.
 */
@Tag("platform-live")
@EnabledIfEnvironmentVariable(named = "GATEWAY_LIVE_APPROVED", matches = "yes")
@EnabledIfEnvironmentVariable(named = "RERO_LIVE_BASE_URL", matches = "https?://.+")
class ReroPlatformEvalIT extends AbstractPostgresIT {

    private static final String TENANT = "tenant-a";
    private static final String TOKEN_URI = "http://localhost:8190/realms/platform/protocol/openid-connect/token";
    private static final Path OUT = EvalHarness.REPORT_DIR.resolve("rero-keep-cut");

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void platform(DynamicPropertyRegistry r) {
        r.add("server.port", () -> System.getenv().getOrDefault("RERO_EVAL_APP_PORT", "8087"));
        r.add("reconcile.gateway.enabled", () -> "true");
        r.add("reconcile.gateway.base-url", () -> System.getenv().getOrDefault("GATEWAY_BASE_URL", "http://localhost:8280"));
        r.add("reconcile.gateway.token-uri", () -> TOKEN_URI);
        r.add("reconcile.gateway.token-scope", () -> "aud-ai-gateway");
        r.add("reconcile.gateway.tenants.[" + TENANT + "].client-id", () -> "svc-reconcile-tenant-a");
        r.add("reconcile.gateway.tenants.[" + TENANT + "].client-secret-env", () -> "KC_SVC_TENANT_A_SECRET");
        r.add("reconcile.knowledge.enabled", () -> "false");
        r.add("reconcile.rero.enabled", () -> "true");
        r.add("reconcile.rero.base-url", () -> System.getenv("RERO_LIVE_BASE_URL"));
        r.add("reconcile.rero.token-uri", () -> TOKEN_URI);
        r.add("reconcile.rero.model-via-gateway", () -> "true");
        r.add("reconcile.rero.tenants.[" + TENANT + "].client-id", () -> "svc-reconcile-tenant-a");
        r.add("reconcile.rero.tenants.[" + TENANT + "].client-secret-env", () -> "KC_SVC_TENANT_A_SECRET");
        // run-token PEMs come from RERO_RUN_TOKEN_PRIVATE_KEY_PEM / RERO_RUN_TOKEN_PUBLIC_KEY_PEM via application.yml
    }

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void keepCutRun() {
        String set = System.getenv().getOrDefault("RERO_EVAL_SET", "primary");
        List<EvalCase> cases = "hard".equals(set) ? new EvalCaseLoader().loadHard() : new EvalCaseLoader().loadAll();
        List<String> engines = Arrays.stream(System.getenv().getOrDefault("RERO_EVAL_ENGINES", "SINGLE_CALL,RERO")
            .split(",")).map(String::strip).toList();
        String stamp = Instant.now().toString().replace(":", "").replace(".", "");

        List<ReroKeepCutReport.Row> baseline = null;
        List<ReroKeepCutReport.Row> rero = null;
        for (String engine : engines) {
            OffsetDateTime since = OffsetDateTime.now().minusSeconds(5);
            String label = "q3-7-" + set + "-" + engine.toLowerCase() + "-gateway-gpt-4o-mini";
            EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), label,
                "Q3.7 keep/cut run, engine " + engine + ", knowledge OFF, model only through ai-gateway (gpt-4o-mini, "
                    + "temperature 0.0). Embeddings: test hashing model.", engine, cases, TENANT);
            assertThat(outcome.results()).hasSize(cases.size());
            List<ReroKeepCutReport.Row> rows = rows(adminJdbc(), engine, outcome.results(), since);
            ReroKeepCutReport.writeCsv(OUT.resolve(set + "-" + engine.toLowerCase() + "-" + stamp + ".csv"), rows);
            if ("RERO".equals(engine)) {
                rero = rows;
            } else {
                baseline = rows;
            }
        }
        String baselineCsv = System.getenv("RERO_EVAL_BASELINE_CSV");
        if (baseline == null && baselineCsv != null && !baselineCsv.isBlank()) {
            baseline = ReroKeepCutReport.readCsv(Path.of(baselineCsv));
        }
        if (baseline != null && rero != null) {
            String safety = System.getenv("RERO_EVAL_SAFETY_ITS");
            ReroKeepCutReport.Comparison c = ReroKeepCutReport.compare(baseline, rero,
                safety == null ? null : "pass".equalsIgnoreCase(safety), duplicateRuns(rero));
            try {
                Files.writeString(OUT.resolve(set + "-comparison-" + stamp + ".md"), c.markdown());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            System.out.println("[q3.7] " + set + "\n" + c.markdown());
        }
    }

    /** Joins each harness result with its job row and the gateway ledger rows for that job. */
    private static List<ReroKeepCutReport.Row> rows(JdbcTemplate admin, String engine, List<EvalCaseResult> results,
                                                    OffsetDateTime since) {
        List<ReroKeepCutReport.Row> rows = new ArrayList<>();
        for (EvalCaseResult r : results) {
            Map<String, Object> job = admin.queryForMap("""
                SELECT j.id::text AS id, j.failure_reason,
                       (extract(epoch FROM (coalesce(j.finished_at, now()) - j.created_at)) * 1000)::bigint AS ms,
                       j.tool_calls_used, j.tool_calls_max, j.outcome_unknown AND j.reconciled_at IS NULL AS unknown,
                       j.rero_run_id::text AS run
                  FROM investigation_job j JOIN break_item b ON b.id = j.break_item_id
                 WHERE j.tenant_id = ? AND j.mode = ? AND j.created_at >= ? AND b.break_reference LIKE ?
                 ORDER BY j.created_at DESC LIMIT 1""", TENANT, engine, since, "EVAL-" + r.caseId() + "-%");
            String jobId = (String) job.get("id");
            Ledger l = ledger(jobId);
            rows.add(new ReroKeepCutReport.Row(r.caseId(), engine, jobId, r.jobSucceeded(),
                r.jobSucceeded() ? null : (String) job.get("failure_reason"), r.expectedRootCause(), r.rootCause(),
                r.expectedRoute(), r.route(), r.citationsValid(),
                REQUIRED.getOrDefault(r.caseId(), 0), r.missingRequiredCitations().size(), ((Number) job.get("ms")).longValue(), r.wallMs(), l.cost(),
                l.rows(), l.statuses(), ((Number) job.get("tool_calls_used")).intValue(),
                job.get("tool_calls_max") == null ? null : ((Number) job.get("tool_calls_max")).intValue(),
                Boolean.TRUE.equals(job.get("unknown")), (String) job.get("run")));
        }
        return rows;
    }

    /** Required evidence files per case (the case's mustCiteEvidence), the denominator of C3 recall. */
    private static final Map<String, Integer> REQUIRED = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        for (EvalCase c : new EvalCaseLoader().loadAll()) {
            REQUIRED.put(c.id(), c.expected().mustCiteEvidence().size());
        }
        for (EvalCase c : new EvalCaseLoader().loadHard()) {
            REQUIRED.put(c.id(), c.expected().mustCiteEvidence().size());
        }
    }

    record Ledger(BigDecimal cost, int rows, String statuses) { }

    /** Ledger rows whose idempotency key carries the job id (SINGLE_CALL: recon:{job}:..., RERO: {job}:draft/selfcheck). */
    private static Ledger ledger(String jobId) {
        String sql = "SELECT coalesce(sum(actual_cost_usd),0)::text || '|' || count(*) || '|' || "
            + "coalesce(string_agg(status::text, ';' ORDER BY created_at),'') FROM invocation_ledger "
            + "WHERE tenant_id = '" + TENANT + "' AND idempotency_key LIKE '%" + jobId + "%'";
        String[] f = psql(sql).split("\\|", -1);
        return new Ledger(new BigDecimal(f[0].strip()), Integer.parseInt(f[1].strip()), f[2].strip());
    }

    /** C6 (ledger part): idempotency keys of this RERO run with more than one ledger row. */
    private static int duplicateRuns(List<ReroKeepCutReport.Row> rero) {
        StringBuilder in = new StringBuilder();
        for (ReroKeepCutReport.Row r : rero) {
            in.append(in.isEmpty() ? "" : " OR ").append("idempotency_key LIKE '%").append(r.jobId()).append("%'");
        }
        String dupKeys = psql("SELECT count(*) FROM (SELECT idempotency_key FROM invocation_ledger WHERE " + in
            + " GROUP BY idempotency_key HAVING count(*) > 1) d");
        return Integer.parseInt(dupKeys.strip()); // RERO's own store (runs per job id) is checked separately
    }

    private static String psql(String sql) {
        try {
            Process p = new ProcessBuilder("docker", "exec", "platform-gw-postgres-1", "psql", "-U", "gateway", "-d",
                "gateway", "-At", "-c", sql).redirectErrorStream(true).start();
            String out;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = String.join("\n", in.lines().toList());
            }
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("ledger query failed: " + out);
            }
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
