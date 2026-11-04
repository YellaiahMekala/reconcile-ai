package com.reconcileai.infrastructure.ai;

import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor;
import com.reconcileai.infrastructure.ai.advisor.TokenUsageAdvisor;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.llm.MockLlmChatModel;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import io.micrometer.core.instrument.MeterRegistry;
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

    @Bean
    ChatClient investigationChatClient(ChatModel chatModel, InjectionScanner scanner, LlmCallRepository llmCalls,
                                       MeterRegistry meters,
                                       @Value("${reconcile.investigation.max-context-tokens}") int maxContextTokens,
                                       @Value("${reconcile.ai.log-prompts:false}") boolean logPrompts) {
        // Hard ceiling = evidence budget + generous allowance for system prompt, schema and break facts (chars ≈ tokens*4).
        int maxPromptChars = (maxContextTokens + 1500) * 4;
        ChatClient.Builder builder = ChatClient.builder(chatModel)
            .defaultAdvisors(new InputGuardAdvisor(scanner, maxPromptChars), new TokenUsageAdvisor(llmCalls, meters));
        if (logPrompts) {
            builder.defaultAdvisors(new SimpleLoggerAdvisor());
        }
        return builder.build();
    }
}
