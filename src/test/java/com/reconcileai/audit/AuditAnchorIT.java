// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.audit;

import com.reconcileai.application.audit.AuditHashing;
import com.reconcileai.application.audit.anchor.AnchorSigner;
import com.reconcileai.application.audit.anchor.AuditAnchorJob;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.reconcileai.support.InvestigationTestSupport.investigate;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit anchoring against MinIO with Object Lock: signed heads are written, the stored object really cannot be deleted,
 * and a CONSISTENT rewrite of the chain (which the chain check alone accepts) is caught by the anchor.
 */
class AuditAnchorIT extends AbstractPostgresIT {

    static final String BUCKET = "audit-anchors";
    static final String ACCESS = "minio-it";
    static final String SECRET = "minio-it-secret";
    static final String[] KEYS = AnchorSigner.generate();

    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("bitnamilegacy/minio:2025.4.22"))
        .withExposedPorts(9000)
        .withEnv("MINIO_ROOT_USER", ACCESS)
        .withEnv("MINIO_ROOT_PASSWORD", SECRET)
        .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        MINIO.start();
    }

    static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void anchorProps(DynamicPropertyRegistry r) {
        r.add("reconcile.audit.anchor.enabled", () -> "true");
        r.add("reconcile.audit.anchor.public-key", () -> KEYS[0]);
        r.add("reconcile.audit.anchor.private-key", () -> KEYS[1]);
        r.add("reconcile.audit.anchor.retention-days", () -> "1");
        r.add("reconcile.audit.anchor.s3.endpoint", AuditAnchorIT::endpoint);
        r.add("reconcile.audit.anchor.s3.bucket", () -> BUCKET);
        r.add("reconcile.audit.anchor.s3.access-key", () -> ACCESS);
        r.add("reconcile.audit.anchor.s3.secret-key", () -> SECRET);
        r.add("reconcile.audit.anchor.s3.path-style", () -> "true");
        r.add("reconcile.audit.anchor.s3.create-bucket", () -> "true");
    }

    @LocalServerPort
    int port;

    @Autowired
    AuditAnchorJob job;

    final String tenant = "TENANT_ANCHOR_" + UUID.randomUUID().toString().substring(0, 6);
    final String analyst = JwtTestSupport.bearer("an-analyst", tenant, List.of("RECON_ANALYST"));
    final String sre = JwtTestSupport.bearer("an-sre", tenant, List.of("SRE_ADMIN"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void headsAreAnchoredInWormStorageAndARewrittenChainIsCaught() {
        investigate(analyst, "50500.00", "50000.00", "/fixtures/evidence/bank-statement-timing.txt");

        // runOnce() counts anchors for ALL tenants in the shared DB, so it cannot prove THIS tenant was anchored;
        // a transient storage error is swallowed per tenant (and retried next cycle), so poll for our own anchor.
        JsonPath anchors = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            job.runOnce();
            anchors = given().header("Authorization", sre).get("/api/v1/audit/anchors").then().statusCode(200).extract().jsonPath();
            if (!anchors.getList("$").isEmpty()) {
                break;
            }
        }
        assertThat(anchors.getList("$")).hasSize(1);
        long anchoredSeq = anchors.getLong("[0].anchoredSequence");
        assertThat(anchoredSeq).isGreaterThanOrEqualTo(1);
        assertThat(given().header("Authorization", sre).get("/api/v1/audit/anchors/verify").jsonPath().getBoolean("valid")).isTrue();

        // nothing new -> nothing written (idempotent)
        job.runOnce();
        assertThat(given().header("Authorization", sre).get("/api/v1/audit/anchors").jsonPath().getList("$")).hasSize(1);

        // the stored object is genuinely write-once: even the bucket owner cannot delete the locked version
        String key = anchors.getString("[0].objectKey");
        String versionId = anchors.getString("[0].objectVersionId");
        assertThatThrownBy(() -> s3().deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).versionId(versionId).build()))
            .isInstanceOf(S3Exception.class);

        // an insider with owner rights rewrites the LAST anchored event AND recomputes its hash: the chain still links...
        rewriteEventConsistently(anchoredSeq);
        assertThat(given().header("Authorization", sre).get("/api/v1/audit/verify").jsonPath().getBoolean("valid"))
            .as("a consistent rewrite passes the chain check on its own").isTrue();

        // ...but it no longer matches the immutable anchor
        JsonPath v = given().header("Authorization", sre).get("/api/v1/audit/anchors/verify").then().statusCode(200).extract().jsonPath();
        assertThat(v.getBoolean("valid")).isFalse();
        assertThat(v.getLong("firstBadSequence")).isEqualTo(anchoredSeq);
        assertThat(v.getString("problem")).contains("no longer matches");
    }

    @Test
    void anchorsAreReadableOnlyByAdminsOfTheSameTenant() {
        given().header("Authorization", analyst).get("/api/v1/audit/anchors").then().statusCode(403);
        String other = JwtTestSupport.bearer("an-other", "TENANT_ANCHOR_NONE", List.of("SRE_ADMIN"));
        assertThat(given().header("Authorization", other).get("/api/v1/audit/anchors").jsonPath().getList("$")).isEmpty();
    }

    @Test
    void signatureCannotBeForgedOrReused() {
        AnchorSigner signer = new AnchorSigner("k", KEYS[0], KEYS[1]);
        String statement = AnchorSigner.statement("T", 5, "a".repeat(64), "2026-01-01T00:00:00Z");
        String sig = signer.sign(statement);
        assertThat(signer.verify(statement, sig)).isTrue();
        assertThat(signer.verify(AnchorSigner.statement("T", 6, "a".repeat(64), "2026-01-01T00:00:00Z"), sig)).isFalse();
        String[] other = AnchorSigner.generate();
        assertThat(new AnchorSigner("k2", other[0], null).verify(statement, sig)).isFalse();
    }

    private void rewriteEventConsistently(long sequence) {
        var jdbc = adminJdbc();
        Map<String, Object> e = jdbc.queryForMap("SELECT * FROM audit_event WHERE tenant_id = ? AND sequence = ?", tenant, sequence);
        String newHash = AuditHashing.hash(((String) e.get("prev_hash")).trim(), tenant, sequence, (String) e.get("event_type"),
            (String) e.get("aggregate_type"), (UUID) e.get("aggregate_id"), "mallory", (String) e.get("details"),
            ((Timestamp) e.get("occurred_at")).toInstant());
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_append_only");
        try {
            jdbc.update("UPDATE audit_event SET actor = 'mallory', hash = ? WHERE tenant_id = ? AND sequence = ?", newHash, tenant, sequence);
            jdbc.update("UPDATE audit_chain_head SET last_hash = ? WHERE tenant_id = ? AND last_sequence = ?", newHash, tenant, sequence);
        } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_append_only");
        }
    }

    private static S3Client s3() {
        return S3Client.builder().httpClient(UrlConnectionHttpClient.create()).region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint()))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET))).build();
    }
}
