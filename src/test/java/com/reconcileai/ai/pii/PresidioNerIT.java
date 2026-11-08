package com.reconcileai.ai.pii;

import com.reconcileai.infrastructure.ai.advisor.PiiMaskingAdvisor;
import com.reconcileai.infrastructure.ai.pii.NerDetector;
import com.reconcileai.infrastructure.ai.pii.PresidioNerDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Presidio analyzer (spaCy model) in a container: names and places are masked before the model call, a UUID chunk
 * id and a business reference survive, and an unreachable sidecar behaves per the fail-open / fail-closed setting.
 */
class PresidioNerIT {

    static GenericContainer<?> presidio;
    static NerDetector detector;

    @BeforeAll
    static void start() {
        presidio = new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/presidio-analyzer:2.2.355"))
            .withExposedPorts(3000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(4)));
        presidio.start();
        detector = new PresidioNerDetector("http://" + presidio.getHost() + ":" + presidio.getMappedPort(3000),
            List.of("PERSON", "LOCATION"), 0.5, Duration.ofSeconds(20));
    }

    @AfterAll
    static void stop() {
        if (presidio != null) {
            presidio.stop();
        }
    }

    @Test
    void masksNamesAndPlacesButKeepsReferencesAndChunkIds() {
        AtomicReference<String> seen = new AtomicReference<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ChatClient client = client(seen, new PiiMaskingAdvisor(meters, detector, true));

        client.prompt().user("Payer Maria Gonzalez in Madrid disputes PO-88410, see chunk 3f2b8c1e-5d4a-4e7b-9c1d-2a6b7c8d9e0f. "
            + "Contact maria.gonzalez@example.org about account 00918273.").call().content();

        String sent = seen.get();
        assertThat(sent).doesNotContain("Maria", "Gonzalez", "Madrid", "maria.gonzalez@example.org", "00918273");
        assertThat(sent).contains("[PERSON]", "[LOCATION]", "[EMAIL]", "[ACCOUNT_NUMBER]");
        assertThat(sent).contains("PO-88410", "3f2b8c1e-5d4a-4e7b-9c1d-2a6b7c8d9e0f");
        assertThat(meters.counter("reconcile.pii.masked", "type", "PERSON").count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void unreachableSidecarFailsClosedOrOpenAsConfigured() {
        NerDetector dead = new PresidioNerDetector("http://localhost:1", List.of("PERSON"), 0.5, Duration.ofSeconds(1));

        AtomicReference<String> closedSeen = new AtomicReference<>();
        assertThatThrownBy(() -> client(closedSeen, new PiiMaskingAdvisor(new SimpleMeterRegistry(), dead, true))
            .prompt().user("Maria Gonzalez").call().content()).isInstanceOf(RuntimeException.class);
        assertThat(closedSeen.get()).isNull();

        AtomicReference<String> openSeen = new AtomicReference<>();
        client(openSeen, new PiiMaskingAdvisor(new SimpleMeterRegistry(), dead, false))
            .prompt().user("Account 00918273").call().content();
        assertThat(openSeen.get()).contains("[ACCOUNT_NUMBER]");
    }

    private static ChatClient client(AtomicReference<String> seen, PiiMaskingAdvisor advisor) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.set(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
            }
        };
        return ChatClient.builder(model).defaultAdvisors(advisor).build();
    }
}
