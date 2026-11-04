package com.reconcileai.ai;

import com.reconcileai.support.AbstractPostgresIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the REAL OpenAI embedding API. Excluded from `mvn verify` by default (failsafe excludedGroups).
 *
 * <p>Run manually (PowerShell):
 * <pre>
 *   $env:OPENAI_API_KEY="sk-..."
 *   mvn verify -Dfailsafe.excludedGroups= -Dit.test=EmbeddingSpikeOpenAiIT
 * </pre>
 */
@Tag("openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles(value = {"openai-it", "mock-llm"}, inheritProfiles = false)
class EmbeddingSpikeOpenAiIT extends AbstractPostgresIT {

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void textEmbedding3SmallReturns1536Dimensions() {
        float[] vector = embeddingModel.embed("Wire fee of USD 500 deducted by intermediary bank");
        assertThat(vector).hasSize(1536);
        assertThat(embeddingModel.getClass().getSimpleName()).contains("OpenAi");
    }
}
