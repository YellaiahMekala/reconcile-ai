package com.reconcileai.support;

import io.restassured.http.ContentType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** Small REST helpers shared by the integration tests, so each test reads as the scenario it proves. */
public final class ApiTestSupport {

    private ApiTestSupport() {}

    /** Creates a USD break with a unique reference and returns its id. */
    public static String createBreak(String bearer, String ledger, String external) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"BRK-%s","ledgerAmount":"%s","externalAmount":"%s","currency":"USD"}
                """.formatted(UUID.randomUUID(), ledger, external))
        .when().post("/api/v1/breaks")
        .then().statusCode(201).extract().path("id");
    }

    /** Uploads a classpath fixture and returns the evidence JSON status field. */
    public static String upload(String bearer, String breakId, String fixture, String contentType) {
        return given().header("Authorization", bearer)
            .multiPart("file", fixture.substring(fixture.lastIndexOf('/') + 1), fixtureBytes(fixture), contentType)
        .when().post("/api/v1/breaks/" + breakId + "/evidence")
        .then().statusCode(201).extract().path("status");
    }

    public static byte[] fixtureBytes(String path) {
        try (InputStream in = ApiTestSupport.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
