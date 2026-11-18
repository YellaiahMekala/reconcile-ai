package com.reconcileai.platform;

import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q4.5 in CI, no key and no spend: EKA is absent (nothing listens on its address, so the connection is
 * refused). With the fail-open default the job still succeeds, the degraded context is visible, and the break is
 * approved and resolved with a valid audit chain.
 */
class PlatformEkaDownIT extends AbstractPlatformDemoIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        register(r, "http://127.0.0.1:1");
    }

    @Test
    void anAbsentEkaDegradesVisiblyAndTheBreakIsStillResolved() {
        JsonPath job = runDemoToResolution();

        assertThat(job.getString("knowledgeOutcome")).isEqualTo("UNAVAILABLE");
        assertThat(job.getString("knowledgeReason")).isNotBlank();
        assertThat(job.getList("knowledgeRefs")).isEmpty();
        assertThat(eka.calls()).as("nothing reached the fake EKA").isEmpty();
    }
}
