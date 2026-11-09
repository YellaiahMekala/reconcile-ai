package com.reconcileai.infrastructure.ai.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Chat model for the investigation agent. Built by hand (LangChain4j core libraries only, no Spring Boot starter) and
 * only when agent mode is switched on. Which model depends on the profile, like the single-call mode:
 * mock-llm gets the scripted model, openai gets the real one. There is no agent model under the ollama profile, so
 * agent requests are refused there.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    @Bean
    @Profile("mock-llm")
    @ConditionalOnProperty(name = "reconcile.agent.enabled", havingValue = "true")
    ChatModel scriptedAgentChatModel() {
        return new ScriptedAgentChatModel();
    }

    @Bean
    @Profile("openai")
    @ConditionalOnProperty(name = "reconcile.agent.enabled", havingValue = "true")
    ChatModel openAiAgentChatModel(@Value("${OPENAI_API_KEY:}") String apiKey,
                                   @Value("${OPENAI_CHAT_MODEL:gpt-4o-mini}") String modelName,
                                   AgentProperties props) {
        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.0)
            .timeout(Duration.ofSeconds(props.timeoutSeconds()))
            .build();
    }
}
