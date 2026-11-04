package com.reconcileai;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Security gate: JWT authentication, role authorization, and tenant isolation.
 *
 * <p>Pass criteria, in the order a request is evaluated:
 * <ol>
 *   <li><b>Authentication</b> - no/expired/forged/tenant-less token => 401 (never 403 or 500).</li>
 *   <li><b>Authorization</b> - valid token, wrong or unknown role => 403.</li>
 *   <li><b>Isolation</b> - valid token, right role, other tenant's data => 404 / absent from lists.</li>
 * </ol>
 *
 * <p><b>RELEASE-BLOCKING.</b> Do not delete, @Disable, or weaken any test in {@link TenantIsolation}.
 * If one fails, the build must fail (maven-failsafe-plugin runs this in `mvn verify`).
 */
class SecurityAndTenantIsolationIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    /** Creates a break as the given caller and returns its id. Unique ref avoids cross-test collisions. */
    private String createBreak(String bearer) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"ISO-%s","ledgerAmount":"100.00","externalAmount":"90.00","currency":"USD"}
                """.formatted(UUID.randomUUID()))
        .when().post("/api/v1/breaks")
        .then().statusCode(201)
            .extract().path("id");
    }

    // 1. Authentication => 401
    @Nested
    class Authentication {

        @Test
        void noTokenIs401() {
            given().when().get("/api/v1/breaks").then().statusCode(401);
        }

        @Test
        void garbageTokenIs401() {
            given().header("Authorization", "Bearer not-a-jwt")
            .when().get("/api/v1/breaks").then().statusCode(401);
        }

        @Test
        void expiredTokenIs401() {
            String token = JwtTestSupport.mintExpired("a1", "TENANT_A", List.of("RECON_ANALYST"));
            given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/breaks").then().statusCode(401);
        }

        @Test
        void tokenSignedByUnknownKeyIs401() {
            String token = JwtTestSupport.mintWithForeignKey("a1", "TENANT_A", List.of("RECON_ANALYST"));
            given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/breaks").then().statusCode(401);
        }

        /** Guards the custom JwtClaimValidator in SecurityConfig. Without it this would be a 500. */
        @Test
        void tokenWithoutTenantClaimIs401() {
            String token = JwtTestSupport.mintWithoutTenant("a1", List.of("RECON_ANALYST"));
            given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/breaks").then().statusCode(401);
        }

        @Test
        void healthIsPublic() {
            given().when().get("/actuator/health").then().statusCode(200);
        }
    }

    // 2. Authorization => 403
    @Nested
    class Authorization {

        /** Guards @EnableMethodSecurity. Without it @PreAuthorize is ignored and this returns 201. */
        @Test
        void approverCannotCreateBreak() {
            given().header("Authorization", JwtTestSupport.bearer("ap1", "TENANT_A", List.of("APPROVER_L1")))
                .contentType(ContentType.JSON)
                .body("""
                    {"breakReference":"ROLE-X","ledgerAmount":"1.00","externalAmount":"1.00","currency":"USD"}
                    """)
            .when().post("/api/v1/breaks")
            .then().statusCode(403);
        }

        /** Unknown role strings are dropped by Role.fromClaim, leaving the caller with no authority. */
        @Test
        void unknownRoleIsForbidden() {
            given().header("Authorization", JwtTestSupport.bearer("x1", "TENANT_A", List.of("ADMIN", "ROLE_SUPERUSER")))
            .when().get("/api/v1/breaks")
            .then().statusCode(403);
        }

        @Test
        void noRolesIsForbidden() {
            given().header("Authorization", JwtTestSupport.bearer("x2", "TENANT_A", List.of()))
            .when().get("/api/v1/breaks")
            .then().statusCode(403);
        }

        @Test
        void approverCanRead() {
            String id = createBreak(JwtTestSupport.bearer("a1", "TENANT_A", List.of("RECON_ANALYST")));
            given().header("Authorization", JwtTestSupport.bearer("ap1", "TENANT_A", List.of("APPROVER_L1")))
            .when().get("/api/v1/breaks/" + id)
            .then().statusCode(200);
        }
    }

    // 3. Tenant isolation => 404 / not listed   (RELEASE-BLOCKING)
    @Nested
    class TenantIsolation {

        final String analystA = JwtTestSupport.bearer("a1", "TENANT_A", List.of("RECON_ANALYST"));
        final String analystB = JwtTestSupport.bearer("b1", "TENANT_B", List.of("RECON_ANALYST"));

        /** 404, not 403: a 403 would confirm to tenant B that tenant A's UUID exists. */
        @Test
        void crossTenantGetIs404() {
            String idA = createBreak(analystA);
            given().header("Authorization", analystB)
            .when().get("/api/v1/breaks/" + idA)
            .then().statusCode(404).body("code", equalTo("BREAK_NOT_FOUND"));
        }

        /** Same response for "exists in other tenant" and "never existed" - no oracle. */
        @Test
        void crossTenantAndNonexistentAreIndistinguishable() {
            String idA = createBreak(analystA);
            String crossTenantCode = given().header("Authorization", analystB)
                .when().get("/api/v1/breaks/" + idA).then().statusCode(404).extract().path("code");
            String missingCode = given().header("Authorization", analystB)
                .when().get("/api/v1/breaks/" + UUID.randomUUID()).then().statusCode(404).extract().path("code");
            org.assertj.core.api.Assertions.assertThat(crossTenantCode).isEqualTo(missingCode);
        }

        @Test
        void crossTenantListExcludesOtherTenantsRows() {
            String idA = createBreak(analystA);
            given().header("Authorization", analystB)
            .when().get("/api/v1/breaks?size=100")
            .then().statusCode(200)
                .body("id", not(hasItem(idA)));
        }

        @Test
        void crossTenantEvidenceUploadIs404() {
            String idA = createBreak(analystA);
            given().header("Authorization", analystB)
                .multiPart("file", "x.txt", "x".getBytes(), "text/plain")
            .when().post("/api/v1/breaks/" + idA + "/evidence")
            .then().statusCode(404);
        }

        @Test
        void crossTenantEvidenceListIs404() {
            String idA = createBreak(analystA);
            given().header("Authorization", analystA)
                .multiPart("file", "a.txt", "tenant A secret".getBytes(), "text/plain")
            .when().post("/api/v1/breaks/" + idA + "/evidence")
            .then().statusCode(201);

            given().header("Authorization", analystB)
            .when().get("/api/v1/breaks/" + idA + "/evidence")
            .then().statusCode(404);
        }

        /**
         * A client cannot pick its tenant via headers or body. The unknown JSON field and the header
         * are both ignored; the break lands in the JWT's tenant (A), so tenant B cannot see it.
         */
        @Test
        void clientSuppliedTenantIsIgnored() {
            String id = given().header("Authorization", analystA)
                .header("X-Tenant-Id", "TENANT_B")
                .contentType(ContentType.JSON)
                .body("""
                    {"breakReference":"SPOOF-%s","ledgerAmount":"1.00","externalAmount":"2.00",
                     "currency":"USD","tenantId":"TENANT_B"}
                    """.formatted(UUID.randomUUID()))
            .when().post("/api/v1/breaks")
            .then().statusCode(201).extract().path("id");

            given().header("Authorization", analystB).when().get("/api/v1/breaks/" + id).then().statusCode(404);
            given().header("Authorization", analystA).when().get("/api/v1/breaks/" + id).then().statusCode(200);
        }

        /** Same break_reference in two tenants is allowed: uniqueness is per tenant. */
        @Test
        void sameReferenceAllowedAcrossTenants() {
            String ref = "SHARED-" + UUID.randomUUID();
            String body = """
                {"breakReference":"%s","ledgerAmount":"1.00","externalAmount":"2.00","currency":"USD"}
                """.formatted(ref);
            given().header("Authorization", analystA).contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/breaks").then().statusCode(201);
            given().header("Authorization", analystB).contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/breaks").then().statusCode(201);
        }

        /** Listing uses a tenant no other test writes to, so the exact count is deterministic. */
        @Test
        void listReturnsOnlyOwnTenantRows() {
            String tenant = "TENANT_LIST_" + UUID.randomUUID();
            String own = JwtTestSupport.bearer("l1", tenant, List.of("RECON_ANALYST"));
            createBreak(own);
            createBreak(own);
            createBreak(analystA); // noise in another tenant

            given().header("Authorization", own)
            .when().get("/api/v1/breaks?size=100")
            .then().statusCode(200)
                .body("size()", equalTo(2))
                .body("breakReference", everyItem(org.hamcrest.Matchers.startsWith("ISO-")));
        }
    }
}
