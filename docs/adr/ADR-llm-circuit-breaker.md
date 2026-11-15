# ADR: retry and circuit breaker around the model call

Status: **implemented (Phase 4C), recorded 2026-11-15.** Code: `infrastructure/ai/resilience/LlmResilience`,
`LlmResilienceProperties`, `LlmUnavailableException`. Tests: `LlmResilienceTest`, `LlmResilienceIT`; the outage
drill in `docs/proof/4c-outage-drill-2026-11-10.md`.

## 1. Context

The model provider is the one dependency this service does not control: it times out, returns 5xx and 429, and
occasionally goes away for minutes. Spring AI's own `RetryTemplate` defaults to 10 attempts, which multiplies a
provider outage into ten paid attempts per investigation and keeps the job thread busy for the whole backoff.

## 2. Decision

One programmatic Resilience4j decorator, `LlmResilience`, around `InvestigationAiClient`'s single model call:

```
call -> CircuitBreaker (OPEN? fail fast, LLM_CIRCUIT_OPEN, zero spend)
          -> Retry (transient only, 3 attempts, 500 ms then 1 s) -> model
```

- **Retried:** network errors, timeouts, HTTP 5xx, HTTP 429, Spring AI `TransientAiException`.
- **Never retried:** a prompt blocked by the input guard (deterministic), `NonTransientAiException` (bad key, bad
  request), invalid model output (a parsing problem is handled downstream, not by paying for the same call again).
- **Breaker:** per instance, named `investigation-llm`; sliding window 10, minimum 5 calls, opens at 50 % failures,
  stays open 30 s, then 2 half-open probes. Retry sits inside the breaker, so one logical call that retried three
  times is one failure, not three. A blocked prompt counts as success for the breaker: it says nothing about the
  provider's health and must not open the circuit for every tenant.
- Spring AI's built-in retry is reduced to 2 attempts (`spring.ai.retry.max-attempts`) so the two layers cannot
  compound into 3 × 10 paid attempts.

### What happens when the provider is down

The job ends **FAILED** with reason `LLM_UNAVAILABLE` (retries exhausted) or `LLM_CIRCUIT_OPEN` (fail fast), and the
break goes to `FAILED_RETRYABLE`. It is **not** routed to `MANUAL_REVIEW`. The earlier Phase 5 plan text said
"fallback routes to MANUAL_REVIEW"; that was the plan, not what was built. Reasons for the built behaviour:

- Manual review is a judgement on a proposal. With no proposal there is nothing for a reviewer to judge, and a
  queue of empty reviews hides an infrastructure problem inside a business queue.
- `FAILED_RETRYABLE` is visible to the recovery sweeper and to the `InvestigationFailuresHigh` and `LlmCircuitOpen`
  alerts, which is where an outage belongs.
- No automatic second provider, ever: the model is untrusted and the provider choice is a deliberate per-profile
  decision. A silent swap would bypass that choice and the cost controls attached to it.

## 3. Why programmatic rather than `@CircuitBreaker` / `@Retry`

Annotations need AOP proxies, which fail silently on self-invocation and on package-private methods, and they
hide the ordering of retry inside breaker. The decorator is explicit, unit-testable without a Spring context, and
its ordering is in one place.

## 4. Observability

`resilience4j_circuitbreaker_state{name="investigation-llm"}`, `resilience4j_retry_calls_total{kind}`,
`reconcile_llm_errors_total{exception}` are on the Grafana dashboard and in the alert rules. An open breaker mutes
the error-rate and failed-job warnings it explains (`docker/observability/alerts.yml`).

## 5. Alternatives considered

- **Spring AI's RetryTemplate alone:** no breaker, no fail-fast, no control over what is transient.
- **Fallback to a second provider:** rejected, see section 2.
- **Breaker per tenant:** rejected; the provider is shared, so its health is global. Per-tenant budgets are a
  different control (the gateway's, in the integration programme).
