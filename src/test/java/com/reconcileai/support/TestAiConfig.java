package com.reconcileai.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * AI beans for the automated test suite. Profile-guarded AND on the test classpath only.
 * application-test.yml sets spring.ai.model.embedding=none so OpenAI's EmbeddingModel is not created.
 */
@Configuration
@Profile("test")
public class TestAiConfig {

    @Bean
    @Primary
    EmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }
}
