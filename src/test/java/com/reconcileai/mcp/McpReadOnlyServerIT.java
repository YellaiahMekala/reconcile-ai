package com.reconcileai.mcp;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1.2.2 on the real app (real Postgres, real JWT validation, Spring AI MCP server over WebMVC, STATELESS):
 * no token 401, wrong role 403, tenant A cannot read tenant B, only read tools are listed, every call is audited
 * without its arguments, a cross-site Origin is refused, and the per-tenant rate limit holds.
 */
class McpReadOnlyServerIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void mcp(DynamicPropertyRegistry r) {
        r.add("spring.ai.mcp.server.enabled", () -> "true");
        r.add("reconcile.mcp.capacity", () -> "50");
        r.add("reconcile.mcp.refill-per-minute", () -> "1");
    }

    @LocalServerPort
    int port;

    private final JdbcTemplate admin = adminJdbc();

    private static final String TENANT_A = "T_MCP_A";
    private static final String TENANT_B = "T_MCP_B";

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static String bearer(String tenant, String role) {
        return JwtTestSupport.bearer("mcp-user-" + tenant, tenant, List.of(role));
    }

    private static String rpc(String method, String params) {
        return """
            {"jsonrpc":"2.0","id":"%s","method":"%s","params":%s}
            """.formatted(UUID.randomUUID(), method, params);
    }

    private static Response post(String bearer, String body) {
        var req = given().contentType(ContentType.JSON).accept("application/json, text/event-stream").body(body);
        if (bearer != null) {
            req = req.header("Authorization", bearer);
        }
        return req.when().post("/mcp");
    }

    private static Response callTool(String bearer, String tool, String args) {
        return post(bearer, rpc("tools/call", "{\"name\":\"%s\",\"arguments\":%s}".formatted(tool, args)));
    }

    private int auditCount(String tenant) {
        return admin.queryForObject("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'MCP_TOOL_CALLED'",
            Integer.class, tenant);
    }

    @Test
    void noTokenIs401() {
        post(null, rpc("tools/list", "{}")).then().statusCode(401);
    }

    @Test
    void wrongRoleIs403() {
        post(bearer(TENANT_A, "SRE_ADMIN"), rpc("tools/list", "{}")).then().statusCode(403);
    }

    @Test
    void crossSiteOriginIsRefused() {
        given().contentType(ContentType.JSON).accept("application/json, text/event-stream")
            .header("Authorization", bearer(TENANT_A, "RECON_ANALYST")).header("Origin", "http://evil.example")
            .body(rpc("tools/list", "{}")).when().post("/mcp").then().statusCode(403);
    }

    @Test
    void toolsListContainsOnlyTheReadTools() {
        List<String> names = post(bearer(TENANT_A, "RECON_ANALYST"), rpc("tools/list", "{}"))
            .then().statusCode(200).extract().jsonPath().getList("result.tools.name", String.class);
        assertThat(names).containsExactlyInAnyOrder(
            "get_break_summary", "list_evidence", "list_recent_breaks", "find_similar_resolved_breaks");
    }

    @Test
    void tenantACannotSeeTenantBAndEveryCallIsAuditedWithoutArguments() {
        String breakOfB = ApiTestSupport.createBreak(bearer(TENANT_B, "RECON_ANALYST"), "1500.00", "1000.00");
        int auditA = auditCount(TENANT_A);
        int auditB = auditCount(TENANT_B);

        // B sees its own break
        String own = callTool(bearer(TENANT_B, "RECON_ANALYST"), "get_break_summary",
            "{\"breakId\":\"" + breakOfB + "\"}").then().statusCode(200).extract().jsonPath().getString("result.content[0].text");
        // plain text, not a JSON-encoded string
        assertThat(own).startsWith("reference=BRK-").contains("\nstatus=").contains("\ncurrency=USD")
            .contains("variance=500");

        // A asking for B's id gets NOT_FOUND, and A's recent list does not include it
        String cross = callTool(bearer(TENANT_A, "APPROVER_L1"), "get_break_summary",
            "{\"breakId\":\"" + breakOfB + "\"}").then().statusCode(200).extract().jsonPath().getString("result.content[0].text");
        assertThat(cross).contains("NOT_FOUND").doesNotContain("currency=");
        String listA = callTool(bearer(TENANT_A, "RECON_ANALYST"), "list_recent_breaks", "{\"limit\":20}")
            .then().statusCode(200).extract().jsonPath().getString("result.content[0].text");
        assertThat(listA).doesNotContain(breakOfB);
        String evidenceA = callTool(bearer(TENANT_A, "RECON_ANALYST"), "list_evidence",
            "{\"breakId\":\"" + breakOfB + "\"}").then().statusCode(200).extract().jsonPath().getString("result.content[0].text");
        assertThat(evidenceA).contains("NOT_FOUND");

        assertThat(auditCount(TENANT_B)).isEqualTo(auditB + 1);
        assertThat(auditCount(TENANT_A)).isEqualTo(auditA + 3);
        List<String> details = admin.queryForList("SELECT details::text FROM audit_event WHERE tenant_id IN (?, ?) "
            + "AND event_type = 'MCP_TOOL_CALLED'", String.class, TENANT_A, TENANT_B);
        assertThat(details).allSatisfy(d -> assertThat(d).contains("\"tool\"").contains("\"outcome\"")
            .doesNotContain(breakOfB).doesNotContain("currency"));
        String actor = admin.queryForObject("SELECT actor FROM audit_event WHERE tenant_id = ? AND event_type = "
            + "'MCP_TOOL_CALLED' ORDER BY sequence DESC LIMIT 1", String.class, TENANT_B);
        assertThat(actor).isEqualTo("mcp-user-" + TENANT_B);
    }

    @Test
    void theRateLimitIsPerTenantAndRefusalsAreAudited() {
        String tenant = "T_MCP_RL";
        String bearer = bearer(tenant, "RECON_ANALYST");
        boolean limited = false;
        for (int i = 0; i < 60 && !limited; i++) {
            var json = callTool(bearer, "list_recent_breaks", "{}").then().statusCode(200).extract().jsonPath();
            limited = Boolean.TRUE.equals(json.get("result.isError"))
                && json.getString("result.content[0].text").contains("RATE_LIMITED");
        }
        assertThat(limited).isTrue();
        assertThat(admin.queryForObject("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = "
            + "'MCP_TOOL_CALLED' AND details::text LIKE '%RATE_LIMITED%'", Integer.class, tenant)).isGreaterThanOrEqualTo(1);
        // another tenant still has its own budget
        assertThat(callTool(bearer("T_MCP_RL2", "RECON_ANALYST"), "list_recent_breaks", "{}")
            .then().statusCode(200).extract().jsonPath().getString("result.content[0].text")).isEqualTo("No breaks.");
    }
}
