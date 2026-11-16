package com.reconcileai.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

/**
 * Platform Q2.7: the ONE paid run, reconcile-ai through ai-gateway to a real provider. Excluded from
 * {@code mvn verify} by the {@code gateway-live} tag AND skipped unless {@code GATEWAY_LIVE_APPROVED=yes} is set in
 * the environment, so it can never run by accident. Follow {@code docs/proof/q2-7-gateway-live-runbook.md}; the
 * evidence block is written to {@code target/q2-7-gateway-live-evidence.md} for the proof document.
 * <p>
 * The gateway must be started with a billable provider and a tenant budget; this test does not configure the
 * gateway. It runs exactly one investigation (one chat call, one permitted transport retry at most).
 */
@Tag("gateway-live")
@EnabledIfEnvironmentVariable(named = "GATEWAY_CLIENT_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GATEWAY_LIVE_APPROVED", matches = "yes")
class GatewayLiveProviderIT extends AbstractRealGatewayIT {

    @Test
    void oneInvestigationThroughTheGatewayToARealProvider() {
        String evidence = runOnce("tenant-a", "/fixtures/evidence/bank-statement-timing.txt");
        writeEvidence(Path.of("target", "q2-7-gateway-live-evidence.md"), "Q2.7 live provider run", evidence);
    }
}
