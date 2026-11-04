package com.reconcileai.support;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;

/** REST helpers for the checker side of the workflow. */
public final class ApprovalTestSupport {

    private ApprovalTestSupport() {}

    public static List<Map<String, Object>> queue(String bearer) {
        return given().header("Authorization", bearer).when().get("/api/v1/approvals")
            .then().statusCode(200).extract().jsonPath().getList("$");
    }

    public static Optional<String> taskIdForProposal(String approverBearer, String proposalId) {
        return queue(approverBearer).stream()
            .filter(t -> proposalId.equals(t.get("proposalId")))
            .map(t -> (String) t.get("id"))
            .findFirst();
    }

    public static ValidatableResponse decide(String bearer, String taskId, String decision, String comment) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body(Map.of("decision", decision, "comment", comment))
        .when().post("/api/v1/approvals/" + taskId + "/decision")
        .then();
    }

    public static String breakStatus(String bearer, String breakId) {
        return given().header("Authorization", bearer).when().get("/api/v1/breaks/" + breakId)
            .then().statusCode(200).extract().path("status");
    }

    public static List<String> auditEventTypes(String bearer, String aggregateId) {
        return given().header("Authorization", bearer).when().get("/api/v1/audit/aggregates/" + aggregateId)
            .then().statusCode(200).extract().jsonPath().getList("eventType");
    }
}
