package com.reconcileai.docs;

import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;

/** Default config exposes no API docs: the paths are not opened, so an anonymous caller gets 401. */
class SwaggerDisabledByDefaultIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Test
    void docsAreNotExposed() {
        RestAssured.port = port;
        given().when().get("/v3/api-docs").then().statusCode(401);
        given().when().get("/swagger-ui.html").then().statusCode(401);
    }
}
