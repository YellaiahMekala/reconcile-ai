package com.reconcileai.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

/**
 * Opt-in: reconcile-ai against the REAL ai-gateway of the platform spike stack (stub provider, so no cost).
 * Excluded from {@code mvn verify} by the {@code gateway} tag.
 * <pre>
 *  bash platform/scripts/spikes/up.sh                      (in the platform repo: Keycloak 8190, gateway 8280)
 *  $env:GATEWAY_CLIENT_SECRET = "..."                      (the svc-reconcile-tenant-a client secret, never in a file)
 *  mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway-live" -Dit.test=RealGatewayIT
 * </pre>
 * Proves the parts a fake cannot: the realm issues a token with {@code aud=ai-gateway} for our scope, the gateway
 * accepts our request shape and key, and the invocation id it returns resolves on its ledger under our token.
 */
@Tag("gateway")
@EnabledIfEnvironmentVariable(named = "GATEWAY_CLIENT_SECRET", matches = ".+")
class RealGatewayIT extends AbstractRealGatewayIT {

    @Test
    void requestIsAcceptedAndTheInvocationResolvesOnTheGatewayLedger() {
        String evidence = runOnce("tenant-a", "/fixtures/evidence/timing-only.txt");
        writeEvidence(Path.of("target", "gateway-spike-evidence.md"), "Q2 against the spike gateway", evidence);
    }
}
