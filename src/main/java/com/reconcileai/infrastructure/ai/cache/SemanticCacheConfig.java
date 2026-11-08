package com.reconcileai.infrastructure.ai.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Chooses the cache implementation. Disabled = a no-op bean; Redis is never contacted. */
@Configuration
public class SemanticCacheConfig {

    @Bean
    SemanticCache semanticCache(SemanticCacheProperties props, ObjectProvider<RedisConnectionFactory> redis,
                                ObjectProvider<EmbeddingModel> embeddings, InjectionScanner scanner,
                                MeterRegistry meters, ObjectMapper json) {
        if (!props.enabled()) {
            return SemanticCache.DISABLED;
        }
        RedisConnectionFactory factory = redis.getIfAvailable();
        EmbeddingModel model = embeddings.getIfAvailable();
        if (factory == null || model == null) {
            throw new IllegalStateException("reconcile.ai.cache.enabled=true needs Redis and an EmbeddingModel");
        }
        return new RedisSemanticCache(new StringRedisTemplate(factory), model, scanner, props, meters, json);
    }
}
