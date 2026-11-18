package com.reconcileai.mcp;

import com.reconcileai.infrastructure.mcp.McpReadTools;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** D1.2.2: with default configuration there is no MCP server, no tool bean and no /mcp endpoint. */
class McpDisabledByDefaultIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void noMcpServerByDefault() {
        RestAssured.port = port;
        assertThat(context.getBeanNamesForType(McpReadTools.class)).isEmpty();
        assertThat(context.getBeanNamesForType(io.modelcontextprotocol.server.McpSyncServer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(io.modelcontextprotocol.server.McpStatelessSyncServer.class)).isEmpty();
        given().contentType(ContentType.JSON).accept("application/json, text/event-stream")
            .header("Authorization", JwtTestSupport.bearer("u", "T_MCP_OFF", List.of("RECON_ANALYST")))
            .body("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\",\"params\":{}}")
            .when().post("/mcp").then().statusCode(404);
    }
}
