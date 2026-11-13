package com.reconcileai.support;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** Starts an investigation and waits (bounded) for the async job to reach a terminal status. */
public final class InvestigationTestSupport {

    private InvestigationTestSupport() {}

    public static String start(String bearer, String breakId, String note) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body(note == null ? "{}" : "{\"analystNote\":\"" + note + "\"}")
        .when().post("/api/v1/breaks/" + breakId + "/investigations")
        .then().statusCode(202).extract().path("id");
    }

    /** Starts an investigation in AGENT mode (the model picks read-only tools). */
    public static String startAgent(String bearer, String breakId) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("{\"mode\":\"AGENT\"}")
        .when().post("/api/v1/breaks/" + breakId + "/investigations")
        .then().statusCode(202).body("mode", org.hamcrest.Matchers.equalTo("AGENT")).extract().path("id");
    }

    /** Starts an investigation in the given mode (SINGLE_CALL, AGENT, SUPERVISED, SUPERVISED_PLANNED), optional note. */
    public static String startMode(String bearer, String breakId, String mode, String note) {
        String body = note == null ? "{\"mode\":\"" + mode + "\"}"
            : "{\"mode\":\"" + mode + "\",\"analystNote\":\"" + note + "\"}";
        return given().header("Authorization", bearer).contentType(ContentType.JSON).body(body)
        .when().post("/api/v1/breaks/" + breakId + "/investigations")
        .then().statusCode(202).body("mode", org.hamcrest.Matchers.equalTo(mode)).extract().path("id");
    }

    /** Break + one fixture + investigation in the given mode, waits for the job; returns the job JSON. */
    public static JsonPath investigateWithMode(String bearer, String breakId, String fixture, String mode, String note) {
        ApiTestSupport.upload(bearer, breakId, fixture, "text/plain");
        return awaitTerminal(bearer, startMode(bearer, breakId, mode, note));
    }

    /** Break + one fixture + agent investigation, waits for the job; returns the job JSON. */
    public static JsonPath investigateWithAgent(String bearer, String breakId, String fixture) {
        ApiTestSupport.upload(bearer, breakId, fixture, "text/plain");
        return awaitTerminal(bearer, startAgent(bearer, breakId));
    }

    public static JsonPath awaitTerminal(String bearer, String jobId) {
        JsonPath[] last = new JsonPath[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            last[0] = given().header("Authorization", bearer).when().get("/api/v1/investigations/" + jobId)
                .then().statusCode(200).extract().jsonPath();
            String s = last[0].getString("status");
            return "SUCCEEDED".equals(s) || "FAILED".equals(s);
        });
        return last[0];
    }

    public static JsonPath proposal(String bearer, String proposalId) {
        return given().header("Authorization", bearer).when().get("/api/v1/proposals/" + proposalId)
            .then().statusCode(200).extract().jsonPath();
    }

    /** Full happy prefix: create break, upload one fixture, investigate, return the proposal JSON. */
    public static JsonPath investigate(String bearer, String ledger, String external, String fixture) {
        String breakId = ApiTestSupport.createBreak(bearer, ledger, external);
        ApiTestSupport.upload(bearer, breakId, fixture, "text/plain");
        JsonPath job = awaitTerminal(bearer, start(bearer, breakId, null));
        if (!"SUCCEEDED".equals(job.getString("status"))) {
            throw new AssertionError("job failed: " + job.getString("failureReason"));
        }
        return proposal(bearer, job.getString("proposalId"));
    }
}
