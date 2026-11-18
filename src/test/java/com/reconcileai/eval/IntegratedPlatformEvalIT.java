package com.reconcileai.eval;

import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q4.3: the 28 labelled cases on the <b>integrated path</b>: EKA knowledge pre-step (real EKA edge) and the
 * single model call through the real ai-gateway (real provider, gpt-4o-mini). PAID; tag {@code platform-live},
 * excluded from the default build and CI, and gated twice: {@code GATEWAY_LIVE_APPROVED=yes} and
 * {@code EKA_LIVE_BASE_URL}. Start the stack with the platform's {@code scripts/golden/up.sh} first.
 * <p>
 * Compare with {@link OpenAiInvestigationEvalIT} run the same day (direct OpenAI, no knowledge): same cases, same
 * model, same test hashing embeddings, so the differences are the gateway route and the procedures in the prompt.
 * Reports, never fails on accuracy; the assertion is that every case produced a result.
 * <pre>
 * GATEWAY_LIVE_APPROVED=yes EKA_LIVE_BASE_URL=http://localhost:8080 KC_SVC_TENANT_A_SECRET=... \
 * mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live,eka" -Dit.test=IntegratedPlatformEvalIT
 * </pre>
 */
@Tag("platform-live")
@EnabledIfEnvironmentVariable(named = "GATEWAY_LIVE_APPROVED", matches = "yes")
@EnabledIfEnvironmentVariable(named = "EKA_LIVE_BASE_URL", matches = "https?://.+")
class IntegratedPlatformEvalIT extends AbstractPostgresIT {

    private static final String TENANT = "tenant-a";
    private static final String TOKEN_URI = "http://localhost:8190/realms/platform/protocol/openid-connect/token";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void platform(DynamicPropertyRegistry r) {
        r.add("reconcile.gateway.enabled", () -> "true");
        r.add("reconcile.gateway.base-url", () -> System.getenv().getOrDefault("GATEWAY_BASE_URL", "http://localhost:8280"));
        r.add("reconcile.gateway.token-uri", () -> TOKEN_URI);
        r.add("reconcile.gateway.token-scope", () -> "aud-ai-gateway");
        r.add("reconcile.gateway.tenants.[" + TENANT + "].client-id", () -> "svc-reconcile-tenant-a");
        r.add("reconcile.gateway.tenants.[" + TENANT + "].client-secret-env", () -> "KC_SVC_TENANT_A_SECRET");
        r.add("reconcile.knowledge.enabled", () -> "true");
        r.add("reconcile.knowledge.base-url", () -> System.getenv("EKA_LIVE_BASE_URL"));
        r.add("reconcile.knowledge.token-uri", () -> TOKEN_URI);
        r.add("reconcile.knowledge.tenants.[" + TENANT + "].client-id", () -> "svc-reconcile-tenant-a");
        r.add("reconcile.knowledge.tenants.[" + TENANT + "].client-secret-env", () -> "KC_SVC_TENANT_A_SECRET");
    }

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void measureTheIntegratedPath() {
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "platform-gateway-eka-gpt-4o-mini",
            "Integrated path: EKA knowledge pre-step (real edge, tenant-a procedures) + one call through ai-gateway "
                + "(real provider gpt-4o-mini, temperature as configured in the gateway). Embeddings: test hashing model.",
            "SINGLE_CALL", new EvalCaseLoader().loadAll(), TENANT);
        assertThat(outcome.results()).hasSize(outcome.summary().cases());
    }
}
