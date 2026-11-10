# 4C proof: real outage → Prometheus → Alertmanager → e-mail (2026-11-10)

End-to-end run of the alerting path against the **real app**, not a synthetic alert. No model call left the machine and
nothing was paid for: the LLM provider was a local WireMock on port 8099 that answered embeddings normally and
returned **503 on chat completions**, and the Presidio PII URL pointed at a closed port. Mail was caught by Mailpit.

## Setup

| Piece | How |
|---|---|
| App | `target/reconcile-ai-0.1.0-SNAPSHOT.jar`, profile `openai`, `SERVER_PORT=8090`, `SPRING_AI_OPENAI_BASE_URL=http://localhost:8099`, `PII_NER_ENABLED=true`, `PII_NER_URL=http://127.0.0.1:9` |
| Fake provider | WireMock 3.13.1 standalone: `POST */embeddings` → 200 with a 1536-dim vector per input; `POST */chat/completions` → 503 |
| Postgres | compose `postgres` on host port 55433 (5432 was taken by another project) |
| Prometheus, Alertmanager, Mailpit | compose `observability` profile; Prometheus scrape target overridden to `host.docker.internal:8090` |
| Load | 1 break, 1 text evidence (READY), 8 investigations 10 s apart |

## What the app did

```
reconcile_investigation_failed_total{reason="LLM_UNAVAILABLE"}   5    # retries exhausted against the 503
reconcile_investigation_failed_total{reason="LLM_CIRCUIT_OPEN"}  3    # fail-fast once the breaker opened
reconcile_llm_errors_total{exception="TransientAiException"}    15    # 5 jobs x (1 call + 2 retries)
reconcile_pii_ner_unavailable_total                             30
resilience4j_circuitbreaker_state{name="investigation-llm",state="open"} 1
```

## What Alertmanager did (times UTC)

| Alert | Prometheus | Alertmanager | E-mail |
|---|---|---|---|
| ReconcileAiDown (critical) | firing after 1 m of failed scrapes | active | 19:13:39 FIRING, 19:17:39 RESOLVED; again 19:18:39 FIRING (app restarted), 19:21:39 RESOLVED |
| LlmCircuitOpen (critical) | firing 19:22:38 | active | 19:23:38 FIRING (group_wait 0 s, so one evaluation after `for: 1m`) |
| PiiNerUnavailable (warning) | firing 19:20:38 | active | 19:26:38 FIRING (warnings batch for 5 m by design) |
| InvestigationFailuresHigh (warning) | firing 19:21:02 | **suppressed** by LlmCircuitOpen | none, as intended |
| LlmErrorRateHigh (warning) | firing 19:25:38 | **suppressed** by LlmCircuitOpen | none, as intended |

Both `ReconcileAiDown` episodes were genuine: the first start failed (a `DB_PASSWORD` variable inherited from another
project's shell overrode the Flyway password), and the second was the deliberate restart against the fake provider.

## The critical e-mail, as received

```
From: reconcile-alerts@localhost
To: oncall@localhost
Subject: [FIRING:1] LlmCircuitOpen (critical)

LlmCircuitOpen - firing
  LLM circuit breaker is open
  Model calls are failing fast. Check the provider status, the API key and quota.
  Since: 2026-11-09 19:22:38 UTC
Dashboards: Grafana | Prometheus alerts
```

## Changes made because of the drill

- New rule `ReconcileAiDown` (`up{job="reconcile-ai"} == 0` for 1 m, critical) with promtool tests: nothing else fires when
  the whole app is gone. 10 rules now.

## Not shown

- The LLM alerts resolving: that needs the provider to answer again (the fake only returns 503). `ReconcileAiDown`
  resolving is shown twice.
- Mail to a real inbox: Alertmanager's SMTP target was Mailpit. Only `smtp_*` in `alertmanager.yml` changes for a real server.
