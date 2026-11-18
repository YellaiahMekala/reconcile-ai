# D1.1.1 knowledge relevance filter (2026-11-18)

Status: **built and tested locally with a schema-checked EKA stub. Effect on route accuracy NOT measured.**

## Why

The Q4.3 ablation (`q4-3-knowledge-ablation-2026-11-18.md`) showed route accuracy 24/28 (85.7%) without knowledge and
19/28 (67.9%) with the EKA knowledge pre-step; the gateway route alone was neutral. tenant-a's corpus held only fee
procedures, so missing-entry and timing breaks received unrelated procedures. n = 28, one run per column, sign test
p = 0.375: a signal worth acting on, not a significant result.

## What was built

- `KnowledgeHit.score`: EKA's `scores.cosine`, a required field of `SearchResultOut.scores` in the copied EKA contract
  (`src/test/resources/contracts/eka/search-response.json`). No field was invented.
- `KnowledgeRelevanceFilter` (application layer, deterministic, no model, no network): keep a hit only if its score is
  at least `reconcile.knowledge.min-score` and, with `reconcile.knowledge.category-match`, it mentions a term of a
  category found in the break's analyst note or retrieved evidence. No category signal in the break: hit kept.
- Defaults: filter on, `min-score` 0.0, category match on. Off-switch `KNOWLEDGE_RELEVANCE_FILTER=false`.
- All hits dropped: outcome `EMPTY`, reason `FILTERED_LOW_RELEVANCE` (existing `knowledge_reason` column, no migration),
  no PROCEDURE block, no `kb:` refs. Audit `KNOWLEDGE_CONSULTED` adds `relevanceKept` / `relevanceDropped` (counts only).
- Fail-open / fail-closed behaviour unchanged.

## What the tests prove

| Test | Proves |
|---|---|
| `KnowledgeRelevanceFilterTest` | inclusive score boundary, missing score, category drop/keep, no-signal keep, all dropped = EMPTY + reason, order kept, non-OK and off-switch pass through |
| `KnowledgeRelevanceIT` | real app + Postgres + EKA stub: a fee procedure is dropped for a missing-entry break (EMPTY, `FILTERED_LOW_RELEVANCE`, no refs, audit counts, no text) and kept for a fee break (OK, `kb:` ref); mixed hits keep only the relevant ref |
| `KnowledgeOutcomeIT`, `KnowledgeFailClosedIT`, `KnowledgeArchitectureTest` | unchanged behaviour of the four outcomes, fail-closed and the search-only rule |

## What is NOT proven

- **Route accuracy with the filter is not measured.** Re-running the 28-case evaluation through the real gateway and EKA
  is a paid run (tracker PQ1.2.4) and needs explicit approval. No improvement is claimed until then.
- The keyword table is author-written and English-only; it can miss a category phrased differently (then the hit is
  either kept for lack of signal or dropped for lack of a matching term).
- `min-score` 0.0 is a placeholder floor: a useful threshold depends on EKA's embedding model and corpus and has not
  been calibrated.
