package com.reconcileai.ai.resilience;

import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor.PromptBlockedException;
import com.reconcileai.infrastructure.ai.resilience.LlmResilience;
import com.reconcileai.infrastructure.ai.resilience.LlmResilienceProperties;
import com.reconcileai.infrastructure.ai.resilience.LlmUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmResilienceTest {

    private static LlmResilience resilience() {
        // 3 attempts, 1 ms backoff, breaker opens at 50% over 4 calls (min 4), stays open 60 s
        return new LlmResilience(new LlmResilienceProperties(3, 1, 50f, 4, 4, 60_000, 1), new SimpleMeterRegistry());
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() {
        LlmResilience r = resilience();
        AtomicInteger calls = new AtomicInteger();
        String out = r.call(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new TransientAiException("503");
            }
            return "ok";
        });
        assertThat(out).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void networkErrorIsTransient() {
        LlmResilience r = resilience();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> r.call(() -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("connect timed out");
        })).isInstanceOf(LlmUnavailableException.class).hasMessage("LLM_UNAVAILABLE");
        assertThat(calls).hasValue(3);
    }

    @Test
    void nonTransientFailureIsNotRetried() {
        LlmResilience r = resilience();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> r.call(() -> {
            calls.incrementAndGet();
            throw new NonTransientAiException("401 invalid api key");
        })).isInstanceOf(NonTransientAiException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void blockedPromptIsNotRetriedAndDoesNotOpenBreaker() {
        LlmResilience r = resilience();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> r.call(() -> {
                calls.incrementAndGet();
                throw new PromptBlockedException("PROMPT_INJECTION_SUSPECTED");
            })).isInstanceOf(PromptBlockedException.class);
        }
        assertThat(calls).hasValue(10);
        assertThat(r.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedOutagesOpenTheBreakerAndThenFailFastWithoutCallingTheModel() {
        LlmResilience r = resilience();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> r.call(() -> {
                calls.incrementAndGet();
                throw new TransientAiException("503");
            })).hasMessage("LLM_UNAVAILABLE");
        }
        assertThat(r.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(calls).hasValue(12); // 4 logical calls x 3 attempts; retry counts as ONE breaker failure

        int before = calls.get();
        assertThatThrownBy(() -> r.call(() -> {
            calls.incrementAndGet();
            return "never";
        })).isInstanceOf(LlmUnavailableException.class).hasMessage("LLM_CIRCUIT_OPEN");
        assertThat(calls).hasValue(before); // zero spend while open

        r.reset();
        assertThat(r.call(() -> "back")).isEqualTo("back");
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new LlmResilienceProperties(0, 1, 50f, 4, 4, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmResilienceProperties(6, 1, 50f, 4, 4, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
