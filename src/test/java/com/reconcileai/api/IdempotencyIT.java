package com.reconcileai.api;

import com.reconcileai.infrastructure.web.IdempotencyPurgeJob;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform D1.1.4: the optional {@code Idempotency-Key} header on POST endpoints that create things. Real application
 * and Postgres; the side effects are counted in the database, not inferred from responses.
 */
class IdempotencyIT extends AbstractPostgresIT {

    private static final String TENANT_A = "TENANT_IDEM_A";
    private static final String TENANT_B = "TENANT_IDEM_B";

    @LocalServerPort
    int port;

    @Autowired
    IdempotencyPurgeJob purgeJob;

    private final JdbcTemplate admin = adminJdbc();
    private String analystA;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        analystA = bearer("idem-a1", TENANT_A);
    }

    private static String bearer(String subject, String tenant) {
        return JwtTestSupport.bearer(subject, tenant, List.of("RECON_ANALYST"));
    }

    private static String breakJson(String ref) {
        return "{\"breakReference\":\"" + ref + "\",\"ledgerAmount\":\"100.00\",\"externalAmount\":\"90.00\",\"currency\":\"USD\"}";
    }

    private static Response postBreak(String auth, String key, String body) {
        var req = given().header("Authorization", auth).contentType(ContentType.JSON).body(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return req.when().post("/api/v1/breaks");
    }

    private int breaks(String tenant, String ref) {
        return admin.queryForObject("SELECT count(*) FROM break_item WHERE tenant_id = ? AND break_reference = ?",
            Integer.class, tenant, ref);
    }

    @Test
    void sameKeyAndSameRequestReplaysTheOriginalResponseWithoutASecondBreak() {
        String ref = "IDEM-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        Response first = postBreak(analystA, key, breakJson(ref));
        // Same meaning, different key order and whitespace: still the same canonical request.
        Response second = postBreak(analystA, key, "{ \"currency\":\"USD\", \"externalAmount\":\"90.00\","
            + " \"ledgerAmount\":\"100.00\", \"breakReference\":\"" + ref + "\" }");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body().asString()).isEqualTo(first.body().asString());
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(second.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(first.header("Idempotency-Replayed")).isNull();
        assertThat(breaks(TENANT_A, ref)).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentPayloadIsRefused() {
        String key = UUID.randomUUID().toString();
        String ref = "IDEM-" + UUID.randomUUID();
        postBreak(analystA, key, breakJson(ref)).then().statusCode(201);

        String other = "IDEM-" + UUID.randomUUID();
        postBreak(analystA, key, breakJson(other)).then().statusCode(422)
            .contentType("application/problem+json")
            .body("code", org.hamcrest.Matchers.equalTo("IDEMPOTENCY_KEY_REUSED"))
            .body("status", org.hamcrest.Matchers.equalTo(422))
            .body("instance", org.hamcrest.Matchers.equalTo("/api/v1/breaks"));
        assertThat(breaks(TENANT_A, other)).isZero();
    }

    @Test
    void keysAreScopedPerTenantAndPerUser() {
        String key = UUID.randomUUID().toString();
        String ref = "IDEM-" + UUID.randomUUID();

        Response a = postBreak(analystA, key, breakJson(ref));
        Response b = postBreak(bearer("idem-b1", TENANT_B), key, breakJson(ref));
        // Same tenant, another user, same key, different payload: not a reuse, because the key is per user.
        String ref2 = "IDEM-" + UUID.randomUUID();
        Response a2 = postBreak(bearer("idem-a2", TENANT_A), key, breakJson(ref2));

        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        assertThat(a2.statusCode()).isEqualTo(201);
        assertThat(b.header("Idempotency-Replayed")).isNull();
        assertThat(b.jsonPath().getString("id")).isNotEqualTo(a.jsonPath().getString("id"));
        assertThat(breaks(TENANT_A, ref)).isEqualTo(1);
        assertThat(breaks(TENANT_B, ref)).isEqualTo(1);
        assertThat(breaks(TENANT_A, ref2)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesSerialiseOnTheKeyAndCreateOneBreak() throws Exception {
        String key = UUID.randomUUID().toString();
        String ref = "IDEM-" + UUID.randomUUID();
        int n = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Response> call = () -> {
                    go.await();
                    return postBreak(analystA, key, breakJson(ref));
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> f : futures) {
                responses.add(f.get());
            }

            assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
            assertThat(responses).extracting(r -> r.jsonPath().getString("id")).containsOnly(
                responses.get(0).jsonPath().getString("id"));
            assertThat(responses).filteredOn(r -> r.header("Idempotency-Replayed") == null).hasSize(1);
            assertThat(breaks(TENANT_A, ref)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedRequestsAreNotStoredSoTheKeyCanBeRetried() {
        String ref = "IDEM-" + UUID.randomUUID();
        postBreak(analystA, null, breakJson(ref)).then().statusCode(201);
        String key = UUID.randomUUID().toString();

        postBreak(analystA, key, breakJson(ref)).then().statusCode(409)
            .body("code", org.hamcrest.Matchers.equalTo("DUPLICATE_BREAK_REFERENCE"));
        String fixed = "IDEM-" + UUID.randomUUID();
        postBreak(analystA, key, breakJson(fixed)).then().statusCode(201);

        assertThat(breaks(TENANT_A, fixed)).isEqualTo(1);
    }

    @Test
    void withoutTheHeaderBehaviourIsUnchanged() {
        String ref = "IDEM-" + UUID.randomUUID();
        postBreak(analystA, null, breakJson(ref)).then().statusCode(201);
        postBreak(analystA, null, breakJson(ref)).then().statusCode(409);
        assertThat(admin.queryForObject("SELECT count(*) FROM idempotency_record WHERE tenant_id = ? "
            + "AND user_subject = 'idem-a1' AND idempotency_key = ?", Integer.class, TENANT_A, ref)).isZero();
    }

    @Test
    void anInvalidKeyIsA400Problem() {
        postBreak(analystA, "x".repeat(129), breakJson("IDEM-" + UUID.randomUUID())).then().statusCode(400)
            .contentType("application/problem+json")
            .body("code", org.hamcrest.Matchers.equalTo("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void evidenceUploadReplayDoesNotUploadTwice() {
        String breakId = ApiTestSupport.createBreak(analystA, "100.00", "90.00");
        String key = UUID.randomUUID().toString();
        byte[] bytes = ApiTestSupport.fixtureBytes("/fixtures/evidence/fee-only.txt");

        Response first = given().header("Authorization", analystA).header("Idempotency-Key", key)
            .multiPart("file", "fee-only.txt", bytes, "text/plain").post("/api/v1/breaks/" + breakId + "/evidence");
        Response second = given().header("Authorization", analystA).header("Idempotency-Key", key)
            .multiPart("file", "fee-only.txt", bytes, "text/plain").post("/api/v1/breaks/" + breakId + "/evidence");

        assertThat(first.statusCode()).isEqualTo(201);
        // Without the key the second upload would be a 200 dedup hit; with it, the original 201 is replayed.
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"));
        assertThat(admin.queryForObject("SELECT count(*) FROM evidence WHERE break_item_id = ?::uuid",
            Integer.class, breakId)).isEqualTo(1);

        // Same key, different file content: refused.
        given().header("Authorization", analystA).header("Idempotency-Key", key)
            .multiPart("file", "fee-only.txt", "other".getBytes(), "text/plain")
            .post("/api/v1/breaks/" + breakId + "/evidence").then().statusCode(422);
    }

    @Test
    void investigationStartReplayDoesNotQueueASecondJob() {
        String breakId = ApiTestSupport.createBreak(analystA, "100.00", "90.00");
        ApiTestSupport.upload(analystA, breakId, "/fixtures/evidence/fee-only.txt", "text/plain");
        String key = UUID.randomUUID().toString();

        Response first = given().header("Authorization", analystA).header("Idempotency-Key", key)
            .contentType(ContentType.JSON).body("{}").post("/api/v1/breaks/" + breakId + "/investigations");
        Response second = given().header("Authorization", analystA).header("Idempotency-Key", key)
            .contentType(ContentType.JSON).body("{}").post("/api/v1/breaks/" + breakId + "/investigations");

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"));
        assertThat(admin.queryForObject("SELECT count(*) FROM investigation_job WHERE break_item_id = ?::uuid",
            Integer.class, breakId)).isEqualTo(1);
    }

    @Test
    void expiredKeysArePurged() {
        String key = "expired-" + UUID.randomUUID();
        admin.update("INSERT INTO idempotency_record (tenant_id, user_subject, endpoint, idempotency_key, request_hash, "
            + "state, created_at, expires_at) VALUES (?, 'idem-a1', 'POST /api/v1/breaks', ?, ?, 'COMPLETED', "
            + "now() - interval '25 hours', now() - interval '1 hour')", TENANT_A, key, "0".repeat(64));

        assertThat(purgeJob.purge()).isGreaterThanOrEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?",
            Integer.class, key)).isZero();
    }
}
