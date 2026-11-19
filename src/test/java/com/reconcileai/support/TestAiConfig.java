// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
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
