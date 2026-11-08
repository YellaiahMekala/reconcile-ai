package com.reconcileai.ai.pii;

import com.reconcileai.infrastructure.ai.advisor.PiiMaskingAdvisor;
import com.reconcileai.infrastructure.ai.pii.NerDetector;
import com.reconcileai.infrastructure.ai.pii.NerDetector.NerUnavailableException;
import com.reconcileai.infrastructure.ai.pii.NerDetector.Span;
import com.reconcileai.infrastructure.ai.pii.NerMasker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offset handling and the fail-open / fail-closed behaviour, without needing the Presidio container. */
class NerMaskingTest {

    @Test
    void replacesSpansRightToLeftAndSkipsOverlapsAndBadOffsets() {
        String text = "Payer Maria Gonzalez of Madrid owes 100";
        var r = NerMasker.apply(text, List.of(
            new Span("PERSON", 6, 20, 0.9),
            new Span("PERSON", 6, 11, 0.6),            // overlaps the longer span -> skipped
            new Span("LOCATION", 24, 30, 0.8),
            new Span("PERSON", 500, 600, 0.9)));      // outside the text -> ignored
        assertThat(r.text()).isEqualTo("Payer [PERSON] of [LOCATION] owes 100");
        assertThat(r.counts()).containsEntry("PERSON", 1).containsEntry("LOCATION", 1);
    }

    @Test
    void failsOpenByDefaultButStillMasksIdentifiers() {
        AtomicReference<String> seen = new AtomicReference<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ChatClient client = client(seen, meters, down(), false);

        client.prompt().user("Account 00918273 for Maria").call().content();

        assertThat(seen.get()).contains("[ACCOUNT_NUMBER]").doesNotContain("00918273");
        assertThat(meters.counter("reconcile.pii.ner.unavailable").count()).isGreaterThan(0.0);
    }

    @Test
    void failClosedRefusesTheModelCallWhenRecognitionIsDown() {
        AtomicReference<String> seen = new AtomicReference<>();
        ChatClient client = client(seen, new SimpleMeterRegistry(), down(), true);

        assertThatThrownBy(() -> client.prompt().user("Maria Gonzalez").call().content())
            .hasRootCauseInstanceOf(NerUnavailableException.class);
        assertThat(seen.get()).as("nothing reached the model").isNull();
    }

    @Test
    void maskedNamesAreCountedPerType() {
        AtomicReference<String> seen = new AtomicReference<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        NerDetector fake = text -> List.of(new Span("PERSON", text.indexOf("Maria"), text.indexOf("Maria") + 5, 0.9));
        client(seen, meters, fake, true).prompt().user("Maria disputes it").call().content();

        assertThat(seen.get()).isEqualTo("[PERSON] disputes it");
        assertThat(meters.counter("reconcile.pii.masked", "type", "PERSON").count()).isEqualTo(1.0);
    }

    private static NerDetector down() {
        return text -> {
            throw new NerUnavailableException("sidecar down", null);
        };
    }

    private static ChatClient client(AtomicReference<String> seen, SimpleMeterRegistry meters, NerDetector ner, boolean failClosed) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.set(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
            }
        };
        return ChatClient.builder(model).defaultAdvisors(new PiiMaskingAdvisor(meters, ner, failClosed)).build();
    }
}
