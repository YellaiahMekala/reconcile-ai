package com.reconcileai.infrastructure.ai.resilience;

import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor.PromptBlockedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Retry + circuit breaker around the investigation model call.
 *
 * <pre>
 *  call ─► CircuitBreaker (OPEN? fail fast with LLM_CIRCUIT_OPEN, zero spend)
 *       └► Retry (transient only, exponential backoff) ─► model
 * </pre>
 *
 * <h2>What is retried</h2>
 * Only failures where a second attempt can succeed: network errors, timeouts, HTTP 5xx, HTTP 429 and Spring AI's
 * {@link TransientAiException}. Never retried: a prompt blocked by the input guard (deterministic, would be blocked
 * again), {@link NonTransientAiException} (bad key, bad request), and invalid model output (a parsing problem is
 * handled downstream, not by paying for the same call again).
 *
 * <h2>What opens the breaker</h2>
 * The same transient failures. A blocked prompt is a successful outcome from the breaker's point of view: it says
 * nothing about the provider's health, so it must never open the circuit for every tenant.
 *
 * <p>Breaker is per instance. Retry sits INSIDE the breaker, so one logical call that retries three times counts
 * as one failure, not three.
 */
@Component
public class LlmResilience {

    private static final Logger log = LoggerFactory.getLogger(LlmResilience.class);
    static final String NAME = "investigation-llm";

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public LlmResilience(LlmResilienceProperties props, MeterRegistry meters) {
        Predicate<Throwable> transientFailure = LlmResilience::isTransient;

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(props.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(props.initialBackoffMs()), 2.0))
            .retryOnException(transientFailure)
            .build();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(props.slidingWindowSize())
            .minimumNumberOfCalls(props.minimumNumberOfCalls())
            .failureRateThreshold(props.failureRateThreshold())
            .waitDurationInOpenState(Duration.ofMillis(props.openStateWaitMs()))
            .permittedNumberOfCallsInHalfOpenState(props.permittedCallsInHalfOpen())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordException(transientFailure)
            .build();

        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        this.circuitBreaker = cbRegistry.circuitBreaker(NAME);
        this.retry = retryRegistry.retry(NAME);

        // resilience4j_circuitbreaker_state, _calls; resilience4j_retry_calls{kind=...} on /actuator/prometheus
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(cbRegistry).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meters);

        circuitBreaker.getEventPublisher().onStateTransition(e ->
            log.warn("LLM circuit breaker {}: {}", NAME, e.getStateTransition()));
        retry.getEventPublisher().onRetry(e ->
            log.info("LLM call retry {}/{} after {}", e.getNumberOfRetryAttempts(), props.maxAttempts() - 1,
                e.getLastThrowable() == null ? "?" : e.getLastThrowable().getClass().getSimpleName()));
    }

    /** Runs the call under breaker + retry. Non-transient exceptions pass through unchanged. */
    public <T> T call(Supplier<T> modelCall) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, modelCall));
        try {
            return decorated.get();
        } catch (CallNotPermittedException open) {
            throw new LlmUnavailableException(LlmUnavailableException.CIRCUIT_OPEN, open);
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                throw new LlmUnavailableException(LlmUnavailableException.RETRIES_EXHAUSTED, e);
            }
            throw e;
        }
    }

    public CircuitBreaker.State state() {
        return circuitBreaker.getState();
    }

    /** Test and operator hook: force the breaker back to CLOSED. */
    public void reset() {
        circuitBreaker.reset();
    }

    static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof PromptBlockedException || c instanceof NonTransientAiException) {
                return false;
            }
            // Amazon Bedrock (AWS SDK): throttling (429) and 5xx are worth retrying; 400/403 (bad request, no model access) are not.
            if (c instanceof AwsServiceException aws) {
                return aws.statusCode() == 429 || aws.statusCode() >= 500;
            }
            if (c instanceof SdkClientException) { // connect/read timeouts and dropped connections
                return true;
            }
            if (c instanceof TransientAiException
                || c instanceof ResourceAccessException
                || c instanceof HttpServerErrorException
                || c instanceof HttpClientErrorException.TooManyRequests
                || c instanceof TimeoutException
                || c instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
