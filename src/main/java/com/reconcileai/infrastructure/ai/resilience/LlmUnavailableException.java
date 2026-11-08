package com.reconcileai.infrastructure.ai.resilience;

/**
 * The model could not be reached: retries were exhausted, or the circuit breaker is open.
 * The message is a short code (LLM_UNAVAILABLE / LLM_CIRCUIT_OPEN) that becomes the job's failure reason;
 * the break goes to FAILED_RETRYABLE so an analyst can re-run it later.
 */
public class LlmUnavailableException extends RuntimeException {

    public static final String RETRIES_EXHAUSTED = "LLM_UNAVAILABLE";
    public static final String CIRCUIT_OPEN = "LLM_CIRCUIT_OPEN";

    public LlmUnavailableException(String code, Throwable cause) {
        super(code, cause);
    }
}
