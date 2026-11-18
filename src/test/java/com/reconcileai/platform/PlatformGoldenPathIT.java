package com.reconcileai.platform;

import com.reconcileai.support.FakeEkaServer;
import com.reconcileai.support.FakeEkaServer.Doc;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform Q4.2 in CI, no key and no spend: knowledge OK, one gateway call, maker-checker approval, audit chain valid. */
class PlatformGoldenPathIT extends AbstractPlatformDemoIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        register(r, FakeEkaServer.get().baseUrl());
    }

    @Test
    void goldenPathFromBreakToResolutionThroughTheGatewayWithKnowledge() {
        Doc runbook = Doc.of("timing-runbook.md", "Timing breaks: confirm the value date on the external statement.");
        eka.index(TENANT, runbook);

        JsonPath job = runDemoToResolution();

        assertThat(job.getString("knowledgeOutcome")).isEqualTo("OK");
        assertThat(job.getList("knowledgeRefs", String.class)).containsExactly("kb:" + runbook.chunkId());
    }
}
