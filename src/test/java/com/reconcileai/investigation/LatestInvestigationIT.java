package com.reconcileai.investigation;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** GET /api/v1/breaks/{breakId}/investigations/latest: the console's page-reload view of a break's newest job. */
class LatestInvestigationIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    final JdbcTemplate jdbc = adminJdbc();
    final String analyst = JwtTestSupport.bearer("maker-1", "TENANT_LATEST", List.of("RECON_ANALYST"));
    final String sre = JwtTestSupport.bearer("sre-1", "TENANT_LATEST", List.of("SRE_ADMIN"));
    final String otherTenant = JwtTestSupport.bearer("x-1", "TENANT_LATEST_OTHER", List.of("RECON_ANALYST", "APPROVER_L2"));
    final String noRole = JwtTestSupport.bearer("nobody", "TENANT_LATEST", List.of());

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private static String latestUrl(String breakId) {
        return "/api/v1/breaks/" + breakId + "/investigations/latest";
    }

    @Test
    void returnsTheNewestOfTwoJobs() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        String first = InvestigationTestSupport.start(analyst, breakId, null);
        InvestigationTestSupport.awaitTerminal(analyst, first);

        // A second job needs the break back in a re-investigable state; seed the newer row directly (real DB).
        UUID newer = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, initiated_by, failure_reason, created_at)
            VALUES (?, 'TENANT_LATEST', ?::uuid, 'FAILED', 'FAILED', 'seed', 'newer-seed', now() + interval '1 minute')""",
            newer, breakId);

        given().header("Authorization", analyst).when().get(latestUrl(breakId))
            .then().statusCode(200)
            .body("id", equalTo(newer.toString()))
            .body("failureReason", equalTo("newer-seed"));

        // The older job is still readable by id and is a different job.
        given().header("Authorization", analyst).when().get("/api/v1/investigations/" + first)
            .then().statusCode(200).body("id", equalTo(first));
    }

    @Test
    void returnsTheSameBodyAsTheJobGet() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        var byId = InvestigationTestSupport.awaitTerminal(analyst, jobId);

        var latest = given().header("Authorization", analyst).when().get(latestUrl(breakId))
            .then().statusCode(200).extract().jsonPath();
        org.assertj.core.api.Assertions.assertThat(latest.getMap("$")).isEqualTo(byId.getMap("$"));
    }

    @Test
    void is404WhenTheBreakHasNoJob() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        given().header("Authorization", analyst).when().get(latestUrl(breakId))
            .then().statusCode(404).body("code", equalTo("JOB_NOT_FOUND"));
    }

    @Test
    void is404ForAnotherTenantsBreak() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));

        given().header("Authorization", otherTenant).when().get(latestUrl(breakId)).then().statusCode(404);
    }

    @Test
    void sameRolesAsTheJobGet() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));

        given().header("Authorization", sre).when().get(latestUrl(breakId)).then().statusCode(200);
        given().header("Authorization", noRole).when().get(latestUrl(breakId)).then().statusCode(403);
        given().when().get(latestUrl(breakId)).then().statusCode(401);
    }
}
