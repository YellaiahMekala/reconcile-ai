# Proof: observability dashboard populated by a golden-path run (2026-11-15)

Closes Phase 5C ("OTel + Micrometer + Grafana"). Everything below was built earlier (Phase 4C); this run records
that the provisioned dashboard, the Prometheus series it reads, the traces and the per-tenant cost data all exist
and fill up from one real run of the application.

## Setup

- `docker compose -f docker/docker-compose.yml --profile observability up -d` (Postgres, Prometheus v2.55.1,
  Grafana 11.3.0, Jaeger 1.62.0, Alertmanager, Mailpit).
- App: `mvn spring-boot:run`, default `mock-llm` chat profile, `TRACING_ENABLED=true`, Java 21 virtual threads on.
  Embeddings are the real OpenAI `text-embedding-3-small` (the only paid calls; well under one cent).
- Driver: `scripts/demo-golden-path.ps1` against tenant `TENANT_DEMO`: create break → upload evidence → investigate →
  proposal `FEE_OR_CHARGE`, confidence 0.880, citations valid, route `L1_APPROVAL` → approve → `RESOLVED` →
  audit chain `valid=true`, 35 events.

## Prometheus: one query per dashboard panel, right after the run

Target `host.docker.internal:8080/actuator/prometheus` health `up`.

| Dashboard panel | Query (from `docker/observability/grafana/dashboards/reconcile-ai.json`) | Result |
|---|---|---|
| LLM estimated spend (USD) | `sum by (model) (reconcile_llm_cost_usd_total)` | `mock-llm` 0.000159 |
| Tokens per minute | `sum by (type) (reconcile_llm_tokens_total)` | prompt 714, completion 86 |
| LLM latency p95 | `histogram_quantile(0.95, sum by (le, model) (rate(reconcile_llm_latency_seconds_bucket[5m])))` | series present; `NaN` until a second sample lands in the 5 m window (`_count` = 1, outcome `OK`) |
| Circuit breaker state | `resilience4j_circuitbreaker_state == 1` | `investigation-llm` state `closed` |
| Retries by outcome | `sum by (kind) (resilience4j_retry_calls_total)` | `successful_without_retry` 1, others 0 |
| PII identifiers masked | `sum by (type) (reconcile_pii_masked_total)` | no series: the sample evidence has no identifiers (expected) |
| HTTP p95 by endpoint | `sum by (uri) (http_server_requests_seconds_count{uri=~"/api/v1.*"})` | 9 URIs, including breaks, evidence, investigations, approvals, audit/verify |
| LLM errors | `sum by (exception) (reconcile_llm_errors_total)` | no series: no error in this run (expected) |

The series the alert rules depend on are the same ones; `ObservabilityIT.seriesUsedByTheAlertRulesAreExposed`
pins their names in CI.

## Grafana

`GET /api/dashboards/uid/reconcile-ai-llm` → title *reconcile-ai: LLM cost, latency and resilience*, 8 panels,
`provisioned: true`; `/api/health` database `ok`, version 11.3.0. Screenshot: `docs/media/5c-grafana-dashboard-2026-11-15.png`
(taken by the owner from http://localhost:3000 after this run).

## Jaeger

`GET /api/traces?service=reconcile-ai` → 20 traces. Operations include `http post /api/v1/breaks/{breakId}/investigations`,
`investigation-job`, `spring_ai chat_client`, `http get /api/v1/investigations/{jobId}` and the scheduled
`investigation-recovery.scheduled-sweep`. The job span is a child of the POST request span (context propagation
through the bounded investigation executor).

## Per-tenant cost: where it lives and why not in Prometheus

The Prometheus series carry **no tenant label** (`grep tenant /actuator/prometheus` → 0 lines; `ObservabilityIT.
prometheusEndpointExposesLlmMetricsWithoutTenantData` enforces it). Reasons: a tenant label is unbounded
cardinality, and tenant identifiers would leave the system through a metrics endpoint. Per-tenant spend is instead
durable and audit-linked in the `llm_call` table (`TokenUsageAdvisor` writes one row per model call). From this
database after the run:

| tenant_id | model | calls | prompt | completion | latency ms |
|---|---|---|---|---|---|
| TENANT_DEMO | mock-llm | 3 | 2129 | 258 | 25 |
| TENANT_DEMO | gpt-4o-mini-2024-07-18 | 2 | 1452 | 226 | 5446 |
| TENANT_A | mock-llm | 4 | 2517 | 337 | 32 |
| TENANT_A | unknown (ERROR rows from the earlier outage drill) | 15 | 0 | 0 | 38935 |
| TENANT_OTHER | gpt-4o-mini-2024-07-18 | 1 | 679 | 116 | 3488 |

(The rows from other dates come from earlier local runs on the same volume.) The original Phase 5C text asked for
"token cost per tenant per day" as a Grafana panel. That is not built, by the decision above. If a tenant view in
Grafana is wanted later, the honest route is a Grafana Postgres datasource reading `llm_call`, not tenant labels on
Prometheus counters.

## What this proof does not show

- No load, no latency benchmark; one investigation.
- The p95 panel needs more than one call per 5 minutes to show a number.
- A real-provider run would show `gpt-4o-mini` in place of `mock-llm`; the series and panels are the same.

## Incidental fix made during this run

`scripts/generate-local-jwt.ps1` failed with "The filename or extension is too long": the test classpath now
exceeds the Windows 32 K command-line limit. The script passes `-cp` through a Java `@argfile` instead. No other
code changed.
