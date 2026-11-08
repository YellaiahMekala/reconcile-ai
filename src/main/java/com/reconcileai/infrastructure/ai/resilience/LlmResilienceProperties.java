package com.reconcileai.infrastructure.ai.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * reconcile.ai.resilience.* — retry and circuit-breaker settings for the investigation model call.
 *
 * @param maxAttempts                total attempts including the first (1 = no retry)
 * @param initialBackoffMs           first wait between attempts; doubles each time (exponential backoff)
 * @param failureRateThreshold       percentage of failed calls in the window that opens the breaker
 * @param slidingWindowSize          number of recent calls the failure rate is computed over
 * @param minimumNumberOfCalls       calls needed before the failure rate is evaluated at all
 * @param openStateWaitMs            how long the breaker stays OPEN before letting probe calls through
 * @param permittedCallsInHalfOpen   probe calls allowed in HALF_OPEN before deciding to close or re-open
 */
@ConfigurationProperties("reconcile.ai.resilience")
public record LlmResilienceProperties(
    int maxAttempts,
    long initialBackoffMs,
    float failureRateThreshold,
    int slidingWindowSize,
    int minimumNumberOfCalls,
    long openStateWaitMs,
    int permittedCallsInHalfOpen
) {
    public LlmResilienceProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("reconcile.ai.resilience.max-attempts must be >= 1");
        }
        if (maxAttempts > 5) {
            // Every retry is a paid model call: cap it so a misconfiguration cannot multiply spend.
            throw new IllegalArgumentException("reconcile.ai.resilience.max-attempts must be <= 5");
        }
    }
}
