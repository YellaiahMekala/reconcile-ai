package com.reconcileai;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Environment spike as a re-runnable test instead of a manual check.
 *
 * <pre>
 *  #  Spike item                                          Where it is proven
 *  1  App boots on Java 21                                contextBootsOnJava21
 *  2  mvn -q dependency:resolve exits 0                   run manually (README "Verify"), can't be a test
 *  3  docker compose up -d postgres healthy (pgvector)    run manually; this class uses the same image
 *  4  One embedding call returns 1536 dims                PHASE 2 (needs OpenAI key + Spring AI starter)
 *  5  Tenant-filtered query returns only expected rows    tenantFilteredQueryReturnsOnlyOwnRows
 *  6  Flyway clean on fresh DB                            flywayCreatedSchema
 *  7  Authenticated endpoint 401 -> 200/201               unauthenticatedIs401 / authenticatedSucceeds
 *  8  One Testcontainers IT passes                        this whole class
 * </pre>
 * The embedding check lives in EmbeddingSpikeOpenAiIT.
 */
class Day0SpikeIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    // Item 1
    @Test
    void contextBootsOnJava21() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
        assertThat(jdbcTemplate).isNotNull();
    }

    // Item 6
    @Test
    void flywayCreatedSchema() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("break_item", "evidence", "flyway_schema_history");

        Integer failed = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    // Item 7a
    @Test
    void unauthenticatedIs401() {
        given().contentType("application/json").body("{}")
        .when().post("/api/v1/breaks")
        .then().statusCode(401);
    }

    // Item 7b
    @Test
    void authenticatedSucceeds() {
        given().header("Authorization", JwtTestSupport.bearer("analyst-1", "TENANT_A", List.of("RECON_ANALYST")))
            .contentType("application/json")
            .body("""
                {"breakReference":"BRK-SPIKE-%s","ledgerAmount":"100.00","externalAmount":"90.00","currency":"USD"}
                """.formatted(UUID.randomUUID()))
        .when().post("/api/v1/breaks")
        .then().statusCode(201).body("id", notNullValue());
    }

    // Item 5 - proven at the SQL level, independent of the REST layer
    @Test
    void tenantFilteredQueryReturnsOnlyOwnRows() {
        String tenantX = "SPIKE_X_" + UUID.randomUUID();
        String tenantY = "SPIKE_Y_" + UUID.randomUUID();
        String x = JwtTestSupport.bearer("x", tenantX, List.of("RECON_ANALYST"));
        String y = JwtTestSupport.bearer("y", tenantY, List.of("RECON_ANALYST"));
        for (String bearer : List.of(x, x, y)) {
            given().header("Authorization", bearer).contentType("application/json")
                .body("""
                    {"breakReference":"S-%s","ledgerAmount":"5.00","externalAmount":"4.00","currency":"USD"}
                    """.formatted(UUID.randomUUID()))
                .when().post("/api/v1/breaks").then().statusCode(201);
        }

        Integer rowsX = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM break_item WHERE tenant_id = ?", Integer.class, tenantX);
        assertThat(rowsX).isEqualTo(2);

        given().header("Authorization", y).when().get("/api/v1/breaks")
        .then().statusCode(200).body("size()", equalTo(1));
    }
}
