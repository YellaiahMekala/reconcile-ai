package com.reconcileai.eval;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness maps model-cited chunk ids to evidence file names. A real model can cite an id that is not a UUID at
 * all (live run 1, 2026-11-13: a UUID with one digit dropped). That crashed the whole comparison with a Postgres cast
 * error. The lookup now compares as text, so a malformed id scores as "not cited" and the run continues.
 */
class EvalRunnerCitedFilesIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    final JdbcTemplate admin = adminJdbc();
    final String analyst = JwtTestSupport.bearer("eval-cf", "TENANT_EVAL_CF", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void aMalformedModelCitedIdScoresAsNotCitedInsteadOfCrashingTheRun() {
        // 36 characters expected; this one has 35 (a digit dropped), the shape seen in the live run.
        List<String> cited = Arrays.asList("8d3d683c-0c02-370e-0e9-75ed8593f972", "not-a-uuid", "", null,
            UUID.randomUUID().toString());

        List<String> files = EvalRunner.citedFiles(admin, cited);

        assertThat(files).isEmpty();
    }

    @Test
    void aRealChunkIdStillMapsToItsEvidenceFileAndDuplicatesCollapse() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        List<String> chunkIds = admin.queryForList(
            "SELECT id::text FROM vector_store WHERE metadata->>'breakItemId' = ? ORDER BY id", String.class, breakId);
        assertThat(chunkIds).isNotEmpty();

        List<String> mixed = new java.util.ArrayList<>(chunkIds);
        mixed.add(chunkIds.get(0));                       // the same chunk cited twice
        mixed.add(chunkIds.get(0).substring(1));          // and once with a dropped character
        List<String> files = EvalRunner.citedFiles(admin, mixed);

        assertThat(files).containsExactly("bank-statement-timing.txt");
    }
}
