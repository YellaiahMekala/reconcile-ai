package com.reconcileai.ai;

import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * RELEASE-BLOCKING: {@link #retrievalNeverCrossesTenants()} and
 * {@link #retrievalNeverCrossesBreaks()} prove the filterExpression / SQL predicates isolate data.
 */
class HybridRetrievalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    HybridRetriever retriever;

    final String analystA = JwtTestSupport.bearer("a1", "TENANT_RA", List.of("RECON_ANALYST"));
    final String analystB = JwtTestSupport.bearer("b1", "TENANT_RB", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void findsRelevantChunkByExactReferenceAndMeaning() {
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        ApiTestSupport.upload(analystA, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        ApiTestSupport.upload(analystA, breakId, "/fixtures/evidence/unrelated-memo.txt", "text/plain");

        List<RetrievedChunk> hits = retriever.retrieve("TENANT_RA", UUID.fromString(breakId), "INV-20931 settled next business day");

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).contains("INV-20931");
        assertThat(hits.get(0).keywordRank()).isEqualTo(1); // exact reference found by the BM25 leg
    }

    @Test
    void retrievalNeverCrossesTenants() {
        // Tenant A and tenant B upload IDENTICAL evidence text.
        String breakA = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        String breakB = ApiTestSupport.createBreak(analystB, "50500.00", "50000.00");
        ApiTestSupport.upload(analystA, breakA, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        ApiTestSupport.upload(analystB, breakB, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        // Tenant B asking with tenant A's break id: service-level 404 before any vector query.
        given().header("Authorization", analystB).queryParam("q", "INV-20931")
        .when().get("/api/v1/breaks/" + breakA + "/evidence/search")
        .then().statusCode(404);

        // Direct retriever call with a MISMATCHED tenant/break pair returns nothing: the filter binds both keys.
        assertThat(retriever.retrieve("TENANT_RB", UUID.fromString(breakA), "INV-20931 settled")).isEmpty();

        // B's own search only returns B's chunks, never A's (ids differ because evidence ids differ).
        List<String> aChunkIds = retriever.retrieve("TENANT_RA", UUID.fromString(breakA), "INV-20931 settled")
            .stream().map(RetrievedChunk::chunkId).toList();
        List<String> bChunkIds = given().header("Authorization", analystB).queryParam("q", "INV-20931 settled")
            .when().get("/api/v1/breaks/" + breakB + "/evidence/search")
            .then().statusCode(200).extract().path("chunkId");
        assertThat(bChunkIds).isNotEmpty().doesNotContainAnyElementsOf(aChunkIds);
    }

    @Test
    void retrievalNeverCrossesBreaks() {
        String break1 = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        String break2 = ApiTestSupport.createBreak(analystA, "10.00", "9.00");
        ApiTestSupport.upload(analystA, break1, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        given().header("Authorization", analystA).queryParam("q", "INV-20931 settled")
        .when().get("/api/v1/breaks/" + break2 + "/evidence/search")
        .then().statusCode(200).body("$", empty());
    }

    @Test
    void outOfCorpusQueryReturnsLittleOrNothing() {
        // Score-floor methodology check: a question unrelated to any evidence should not surface the statement.
        String breakId = ApiTestSupport.createBreak(analystA, "50500.00", "50000.00");
        ApiTestSupport.upload(analystA, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        given().header("Authorization", analystA).queryParam("q", "volcano penguin orchestra")
        .when().get("/api/v1/breaks/" + breakId + "/evidence/search")
        .then().statusCode(200).body("fileName", not(hasItem("bank-statement-timing.txt")));
    }
}
