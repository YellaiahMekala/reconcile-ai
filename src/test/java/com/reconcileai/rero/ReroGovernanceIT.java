package com.reconcileai.rero;

import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.AbstractReroIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Platform Q3.5 governance: with reconcile-ai's gateway route on, RERO is refused with {@code MODE_NOT_GOVERNED}
 * unless the operator declares {@code reconcile.rero.model-via-gateway=true} (RERO runs GatewayModel). Default false.
 */
class ReroGovernanceIT extends AbstractGatewayIT {

    @DynamicPropertySource
    static void rero(DynamicPropertyRegistry r) {
        AbstractReroIT.register(r);
    }

    @Test
    void reroIsNotGovernedUnlessDeclaredWhileTheGatewayRouteIsOn() {
        String analyst = JwtTestSupport.bearer("rero-gov", "TENANT_RERO_GOV", List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        given().header("Authorization", analyst).contentType("application/json").body("{\"mode\":\"RERO\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(409).body("code", equalTo("MODE_NOT_GOVERNED"));
    }
}
