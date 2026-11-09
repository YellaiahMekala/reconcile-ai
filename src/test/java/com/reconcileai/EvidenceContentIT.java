package com.reconcileai;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The original upload is kept, downloadable by the tenant that owns it, and invisible to everyone else. */
class EvidenceContentIT extends AbstractPostgresIT {

    private static final String FIXTURE = "/fixtures/evidence/bank-statement-timing.txt";

    @LocalServerPort
    int port;

    String analystA;
    String analystB;
    String approverA;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        analystA = "Bearer " + JwtTestSupport.mint("a1", "TENANT_EC_A", List.of("RECON_ANALYST"));
        analystB = "Bearer " + JwtTestSupport.mint("b1", "TENANT_EC_B", List.of("RECON_ANALYST"));
        approverA = "Bearer " + JwtTestSupport.mint("ap1", "TENANT_EC_A", List.of("APPROVER_L1"));
    }

    private String uploadedEvidenceId(String breakId) {
        ApiTestSupport.upload(analystA, breakId, FIXTURE, "text/plain");
        return given().header("Authorization", analystA).when().get("/api/v1/breaks/" + breakId + "/evidence")
            .then().statusCode(200).extract().path("[0].id");
    }

    @Test
    void uploadedBytesComeBackIdentical() {
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        String id = uploadedEvidenceId(breakId);

        byte[] downloaded = given().header("Authorization", analystA)
            .when().get("/api/v1/breaks/" + breakId + "/evidence/" + id + "/content")
            .then().statusCode(200)
            .header("Content-Disposition", containsString("attachment"))
            .header("X-Content-Type-Options", equalTo("nosniff"))
            .contentType("application/octet-stream")
            .extract().asByteArray();
        assertArrayEquals(ApiTestSupport.fixtureBytes(FIXTURE), downloaded);

        // approvers read the audit material too
        given().header("Authorization", approverA)
            .when().get("/api/v1/breaks/" + breakId + "/evidence/" + id + "/content").then().statusCode(200);
    }

    @Test
    void duplicateUploadKeepsOneCopy() {
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        uploadedEvidenceId(breakId);
        given().header("Authorization", analystA)
            .multiPart("file", "bank-statement-timing.txt", ApiTestSupport.fixtureBytes(FIXTURE), "text/plain")
            .when().post("/api/v1/breaks/" + breakId + "/evidence").then().statusCode(200);

        Integer rows = adminJdbc().queryForObject("""
            SELECT count(*) FROM evidence_content c JOIN evidence e ON e.id = c.evidence_id
            WHERE e.break_item_id = ?::uuid""", Integer.class, breakId);
        assertEquals(1, rows);
    }

    @Test
    void otherTenantGets404() {
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        String id = uploadedEvidenceId(breakId);

        given().header("Authorization", analystB)
            .when().get("/api/v1/breaks/" + breakId + "/evidence/" + id + "/content")
            .then().statusCode(404);
    }

    @Test
    void unknownEvidenceIdIs404AndNoTokenIs401() {
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        String url = "/api/v1/breaks/" + breakId + "/evidence/" + UUID.randomUUID() + "/content";

        given().header("Authorization", analystA).when().get(url)
            .then().statusCode(404).body("code", equalTo("EVIDENCE_NOT_FOUND"));
        given().when().get(url).then().statusCode(401);
    }

    @Test
    void rowsFromBeforeTheMigrationReportContentNotAvailable() {
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        String id = uploadedEvidenceId(breakId);
        adminJdbc().update("DELETE FROM evidence_content WHERE evidence_id = ?::uuid", id); // simulate a pre-V9 row

        given().header("Authorization", analystA)
            .when().get("/api/v1/breaks/" + breakId + "/evidence/" + id + "/content")
            .then().statusCode(404).body("code", equalTo("CONTENT_NOT_AVAILABLE"));
    }
}
