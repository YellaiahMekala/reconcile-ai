package com.reconcileai.infrastructure.ai;

import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor;
import com.reconcileai.infrastructure.ai.advisor.PiiMaskingAdvisor;
import com.reconcileai.infrastructure.ai.pii.NerDetector;
import com.reconcileai.infrastructure.ai.pii.PresidioNerDetector;
import com.reconcileai.infrastructure.ai.advisor.TokenUsageAdvisor;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.llm.MockLlmChatModel;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the investigation ChatClient and its advisor chain.
 *
 * <pre>
 *  ChatClient ─► InputGuardAdvisor (block before spend)
 *             ─► PiiMaskingAdvisor (mask accounts/IBANs/cards/emails/phones; reconcile.ai.pii-masking.enabled)
 *             ─► TokenUsageAdvisor (measure what was spent)
 *             ─► [SimpleLoggerAdvisor only if reconcile.ai.log-prompts=true — localhost debugging]
 *             ─► ChatModel  (MockLlmChatModel | Ollama, chosen by profile; exactly one bean exists)
 * </pre>
 *
 * <p>The ChatClient is built explicitly from the single ChatModel bean rather than via the auto-configured
 * ChatClient.Builder, so the advisor order is visible in one place.
 *
 * <p>SimpleLoggerAdvisor logs FULL prompts, which contain evidence text (customer financial data). It is off by default
 * and must never be enabled outside a developer machine.
 */
@Configuration
public class AiConfig {

    @Bean
    @Profile("mock-llm")
    ChatModel mockLlmChatModel() {
        return new MockLlmChatModel();
    }

    /** Present only when a Presidio sidecar is configured; masks person names and locations (see README). */
    @Bean
    @ConditionalOnProperty(name = "reconcile.ai.pii-masking.ner.enabled", havingValue = "true")
    NerDetector presidioNerDetector(@Value("${reconcile.ai.pii-masking.ner.url}") String url,
                                    @Value("${reconcile.ai.pii-masking.ner.entities:PERSON,LOCATION}") List<String> entities,
                                    @Value("${reconcile.ai.pii-masking.ner.score-threshold:0.5}") double threshold,
                                    @Value("${reconcile.ai.pii-masking.ner.timeout-ms:3000}") long timeoutMs) {
        return new PresidioNerDetector(url, entities, threshold, Duration.ofMillis(timeoutMs));
    }

    @Bean
    ChatClient investigationChatClient(ChatModel chatModel, InjectionScanner scanner, LlmCallRepository llmCalls,
                                       MeterRegistry meters,
                                       @Value("${reconcile.investigation.max-context-tokens}") int maxContextTokens,
                                       @Value("${reconcile.ai.log-prompts:false}") boolean logPrompts,
                                       @Value("${reconcile.ai.pii-masking.enabled:true}") boolean piiMasking,
                                       ObjectProvider<NerDetector> ner,
                                       @Value("${reconcile.ai.pii-masking.ner.fail-closed:false}") boolean nerFailClosed,
                                       @Value("${reconcile.ai.pricing.prompt-usd-per-million:0}") double promptPrice,
                                       @Value("${reconcile.ai.pricing.completion-usd-per-million:0}") double completionPrice,
                                       ObjectProvider<ObservationRegistry> observations) {
        // Hard ceiling = evidence budget + generous allowance for system prompt, schema and break facts (chars ≈ tokens*4).
        int maxPromptChars = (maxContextTokens + 1500) * 4;
        ChatClient.Builder builder = ChatClient.builder(chatModel, observations.getIfAvailable(() -> ObservationRegistry.NOOP), null, null)
            .defaultAdvisors(new InputGuardAdvisor(scanner, maxPromptChars),
                new TokenUsageAdvisor(llmCalls, meters, promptPrice, completionPrice));
        if (piiMasking) {
            builder.defaultAdvisors(new PiiMaskingAdvisor(meters, ner.getIfAvailable(), nerFailClosed));
        }
        if (logPrompts) {
            builder.defaultAdvisors(new SimpleLoggerAdvisor());
        }
        return builder.build();
    }
}
