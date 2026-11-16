# Proof: semantic cache savings, measured on the running app (2026-11-15)

Closes Phase 5D ("circuit breaker, semantic cache, outbox"). The three pieces were built in Phase 4C and 4F; the
decisions are in `docs/adr/ADR-llm-circuit-breaker.md`, `ADR-semantic-cache.md` and `ADR-outbox-kafka.md`. This
document records one measurement of the cache against the live application.

## Setup

- App with `CACHE_ENABLED=true`, Redis from the `cache` Compose profile, Java 21 virtual threads on.
- Chat is the deterministic `mock-llm` profile; embeddings are the real OpenAI `text-embedding-3-small` (the only
  paid calls, well under one cent in total). Tenant `TENANT_CACHE`, analyst note `Variance is 500 USD; check fees.`
- Token counts are the ones `TokenUsageAdvisor` wrote to `llm_call`. Dollar figures are **estimates at
  gpt-4o-mini list prices** (USD 0.15 per million prompt tokens, 0.60 per million completion tokens), the same
  rates the cost counter uses. They are not a provider invoice, because no real chat call was made.

## Runs

| # | What the analyst did | Cache result | `llm_call` row | Tokens (prompt / completion) |
|---|---|---|---|---|
| 1 | Investigate break A (1 evidence file) | `miss`, then `store` | `mock-llm`, `OK` | 713 / 86 |
| 2 | Break B, same evidence text, same note | `miss`, then `store` | `mock-llm`, `OK` | 713 / 86 |
| 3 | Reject A's proposal, investigate A again, same note | `hit_exact` | `cache-exact`, `CACHE_HIT` | 0 / 0 |
| 4 | Reject, investigate A again, reworded note (`Variance 500 USD, please check the fees.`) | `hit_semantic` | `cache-semantic`, `CACHE_HIT` | 0 / 0 |
| 5 | Reject, investigate A again, original note | `hit_exact` | `cache-exact`, `CACHE_HIT` | 0 / 0 |
| 6 | Reject, upload a second evidence file to A, investigate A again | `miss`, then `store` | `mock-llm`, `OK` | 833 / 96 |

Counters after the run: `reconcile_llm_cache_total` `hit_exact` 2, `hit_semantic` 1, `miss` 3, `store` 3.

What the table shows:

- **Repeats of the same break and evidence are served without a model call** (runs 3 to 5), including a reworded
  note (run 4, semantic tier).
- **Different evidence forces a fresh call** (runs 2 and 6). Run 2 has the same text as run 1 but a different
  break, so a different evidence snapshot; run 6 adds a file, so the chunk-id set changed. This is the
  evidence-snapshot rule in the ADR working as intended: a similar question over different evidence is not
  answered from the cache.
- **The hit does not change the decision.** All six proposals are `FEE_OR_CHARGE`, confidence 0.880, citations
  valid, route `L1_APPROVAL`; run 6 cites 2 chunks, the others 1. Citation verification and the policy gate ran
  on every hit, and each hit has its own audit trail and `llm_call` row.

## Savings arithmetic

The three hits (runs 3 to 5) each avoided one call of about 713 prompt and 86 completion tokens:

- Avoided per hit: 713 x 0.15 / 1e6 + 86 x 0.60 / 1e6 = about USD 0.000159.
- Avoided for the three hits: about USD 0.00048, against 2,259 prompt and 268 completion tokens actually spent
  across the three misses (about USD 0.00050).
- The semantic tier (run 4) paid one embedding call for its lookup; that cost is a small fraction of the chat call
  it replaced.

## What this does not show

- **Not a production hit rate.** Every hit here was manufactured by re-running the same break. Real savings depend
  on how often analysts repeat an investigation, and that was not measured.
- **Mock chat, so the dollars are list-price estimates** for a 713 / 86 token call of the size the mock produces.
  A real `gpt-4o-mini` call would use different token counts. The mechanism (zero tokens on a hit) is the same.
- **Latency is not claimed.** The mock answers in about 5 ms, so the saving on a real provider (seconds) cannot be
  read from this run.
- The cache does not serve "a similar break elsewhere", by design (see the ADR).

## How to reproduce

`docker compose -f docker/docker-compose.yml --profile cache up -d`, start the app with `CACHE_ENABLED=true`, then
investigate a break, reject the proposal, and investigate it again. Query:
`select model, prompt_tokens, completion_tokens, outcome from llm_call where tenant_id = '<tenant>' order by created_at;`
and `curl -s localhost:8080/actuator/prometheus | grep reconcile_llm_cache_total`.
