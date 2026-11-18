# ADR: Redis semantic cache of verified drafts

Status: **implemented (Phase 4F), off by default, recorded 2026-11-15.** Code: `infrastructure/ai/cache/`
(`RedisSemanticCache`, `SemanticCacheConfig`, `SemanticCacheProperties`). Tests: `SemanticCacheIT` (real Redis),
`SemanticCacheMathTest`. Savings measurement: `docs/proof/5d-semantic-cache-savings-2026-11-15.md`.

## 1. Context

The expensive step of an investigation is the model call. Repeated investigations of the same break (an analyst
re-runs after adding a note, a recovery re-run, a demo) ask the model the same question over the same evidence.
The design constraint is that a cache must not weaken the "model proposes, Java decides" boundary: a cached answer
is still an untrusted model answer.

## 2. Decision

A two-tier cache in Redis, **per tenant**, in front of the model call only:

1. A suspect analyst note (injection scanner) bypasses the cache so the input guard can block it; a cache must never
   answer a prompt the guard would have refused.
2. **Exact** tier: `sha256(namespace | system prompt | user prompt)`.
3. **Semantic** tier: embed the PII-masked prompt, cosine against the tenant's newest N entries, hit only if the
   similarity is at least the threshold (default 0.95; configuration below 0.80 is refused at startup) **and** the
   entry's evidence snapshot (sorted chunk ids) is identical to this prompt's.

The cache replaces the model call and nothing else. Normalisation, citation verification against the current
retrieval snapshot, the policy gate and the audit trail all run on a hit exactly as on a miss. Only drafts whose
citations verified are ever stored. A hit is recorded in `llm_call` as model `cache-exact` or `cache-semantic` with
outcome `CACHE_HIT` and 0 tokens, so per-tenant cost reporting stays truthful.

### The evidence-snapshot rule

A similar question over different evidence is a different question. Requiring the identical chunk-id set means a
cached draft can only cite chunks that are in this retrieval snapshot, so citation verification stays meaningful,
and new or changed evidence always forces a fresh model call. In practice this means the cache serves repeated
investigations of the same break and evidence; it does not serve "a similar break elsewhere", by design.

### Failure policy

Fail open. Any Redis or embedding error is a miss and a metric, never a failed investigation. The lookup has a
500 ms timeout so a slow cache cannot slow investigations. Redis joins the health check only when the cache is on.

### Privacy

The embedding is computed on PII-masked text. Prompts are never stored; the entry holds the embedding, the draft and
the chunk ids. Keys are namespaced per tenant (`rai:{ns}:{tenant}:...`), so no lookup can cross tenants.

## 3. Why not Caffeine only, and why no vector index

A heap-only L1 is lost on restart and is not shared between instances behind the load balancer; the Kubernetes
deployment runs 2 to 6 replicas. Redis is already present for SSE fan-out. Semantic search is a bounded linear scan
(newest N, default bounded by `max-entries-per-tenant`) rather than RediSearch: no Redis Stack module to operate,
and N × 1536 floats is sub-millisecond arithmetic at this scale. A RediSearch KNN index is the upgrade path.

Update (D1.2.1, 2026-11-18): Caffeine is now added as an optional L1 **in front of** Redis, not instead of it
(`TieredSemanticCache`, `CACHE_L1_ENABLED`, off by default). Redis stays the shared tier; L1 keys carry tenant,
namespace and evidence snapshot, and the L1 TTL never exceeds the Redis TTL. Proof:
`docs/proof/d1-2-1-l1-cache-latency-2026-11-18.md`.

## 4. Off by default

Like auto-resolve (ADR-9) and Kafka, new infrastructure is opt-in: `CACHE_ENABLED=true` plus Redis from the
`cache` Compose profile. Turning it on without Redis fails at startup with a clear message rather than silently
running without a cache.

## 5. Observability

`reconcile_llm_cache_total{result=hit_exact|hit_semantic|miss|store|error}`, `reconcile_llm_cache_similarity`;
`llm_call` rows with outcome `CACHE_HIT`; the daily digest counts cache hits per tenant.

## 6. Limits

- Savings depend on repetition. The measurement in the proof document shows the per-hit saving; it is not a
  claim about a production hit rate, which depends on the workload.
- The semantic tier costs one embedding call per lookup (`text-embedding-3-small`), a small fraction of a chat
  call; a workload with no repeats pays that for nothing, which is another reason the cache is opt-in.
- Namespace bump (`reconcile.ai.cache.namespace`) is the only invalidation for a prompt or model change; entries
  otherwise expire by TTL (24 h default).
