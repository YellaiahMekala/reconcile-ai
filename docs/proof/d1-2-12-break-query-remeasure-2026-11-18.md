# D1.2.12: route accuracy re-measured with the break-specific EKA query (2026-11-18)

Status: **run 2026-11-18, local, two runs, paid and approved (cap 0.02 USD).** Same 28 synthetic cases, same model
(`gpt-4o-mini` through the real ai-gateway), same test hashing embeddings, same harness (`IntegratedPlatformEvalIT`, tag
`platform-live`) and the same three fee procedures in EKA as `pq1-2-4-relevance-filter-remeasure-2026-11-18.md`. What
changed: `KNOWLEDGE_BREAK_SPECIFIC_QUERY=true` (now the default), so the EKA query is built per break by
`BreakKnowledgeQuery`; and `min-score` was re-fixed from free probes (section 3).

**Result: no improvement that the sample can show.** Route 23/28 (82.1%) in run 1 and 22/28 (78.6%) in run 2, against
21/28 with the generic query and the filter (PQ1.2.4), 19/28 unfiltered, and 24/28 without knowledge. The run-to-run
difference (one case) is as large as the gain over PQ1.2.4 (1 to 2 cases). The three missing-entry cases (MIS-001 to
MIS-003) still go to manual review.

## 1. Comparison

| Metric | Baseline (direct, no knowledge) | Gateway only | Integrated, unfiltered | PQ1.2.4 (generic query + filter) | This work, run 1 | This work, run 2 |
|---|---|---|---|---|---|---|
| Route correct | 24/28 (85.7%) | 24/28 (85.7%) | 19/28 (67.9%) | 21/28 (75.0%) in 3 runs | **23/28 (82.1%)** | **22/28 (78.6%)** |
| Root cause correct | 27/28 | 27/28 | 27/28 | 27/28 | 26/28 (one job refused, see 4) | 27/28 |
| Citations valid | 100% | 96.0% | 100% | 100% | 100% | 100% |
| Required evidence cited | n/a here | n/a here | n/a here | n/a here | 95.8% (the refused job) | 100% |
| Latency p50 / p95 | 2634 / 3555 ms | 2853 / 4063 ms | 2779 / 3825 ms | 3085 to 3290 / 3776 to 4319 ms | 2913 / 3879 ms | 3047 / 4062 ms |
| Tokens in / out | 15964 / 2952 | 15951 / 2969 | 24414 / 2989 | about 20200 / 2990 | 19328 / 2892 | 20210 / 2948 |

95% Wilson intervals: run 1 route 64.4% to 92.1%; run 2 route 60.5% to 89.8%; baseline 24/28 is 68.5% to 94.3%;
PQ1.2.4 21/28 is 56.7% to 87.3%; unfiltered 19/28 is 49.3% to 82.1%. Every interval contains the baseline value. With
n = 28 this cannot show a difference from the baseline, from PQ1.2.4, or from unfiltered knowledge. Repeats measure
model noise only; they do not widen the set of cases.

## 2. Per-case flips

Route misses: run 1 = FXR-003, MIS-001, MIS-002, MIS-003 (job refused, counted as a miss), PAR-002 (5).
Run 2 = FXR-003, MIS-001, MIS-002, MIS-003, PAR-002, TIM-002 (6).

| Against | Cases that flipped |
|---|---|
| PQ1.2.4 (misses FXR-003, MIS-001 to 003, PAR-002, TIM-002, TIM-004) | TIM-004 now correct in both runs; TIM-002 correct in run 1 only (a miss again in run 2). No case got worse |
| Baseline 24/28 (its four misses, by elimination: FXR-003, PAR-002, TIM-002, TIM-004) | Lost to knowledge: MIS-001, MIS-002, MIS-003 (all runs). Gained: TIM-004 (both runs), TIM-002 (run 1 only). Net 23 and 22 |
| Unfiltered 19/28 | Per-case results of that run were not re-derived here; the totals are +4 (run 1) and +3 (run 2) over it, with MIS-001 to MIS-003 still lost |

TIM-002 changing between runs at the same settings is the noise floor: a one-case move is not evidence.

## 3. min-score: re-probed free, fixed before any paid call

Rule (written before the paid runs): **min-score = midpoint between the highest cosine of an unrelated query and the
lowest cosine returned for any per-break query**, so a hit scoring like an unrelated query is dropped and a hit scoring
like any real break is kept. Search calls only; no model call and no spend (the probe ran `IntegratedPlatformEvalIT`
with the gateway address pointing at a closed port, so no model call could happen, and without the provider key in the
test process).

| Probe | Cosine of the three fee procedures |
|---|---|
| Six unrelated queries (cafeteria menu, marketing, parking, leave, laptop password, sales template) | 0.403 to 0.569 (highest 0.569, "annual leave request approval steps") |
| Per-break queries of the 25 cases that have evidence and so call EKA (the other three, UNK-001 to UNK-003, have no evidence and never call) | 0.657 to 0.758 (lowest hit DAT-001 0.657; highest FEE-002 0.758) |

Midpoint of 0.569 and 0.657 = 0.613, so **min-score = 0.61** (PQ1.2.4 used 0.60). Not changed between runs.

What the probe shows, honestly:

- Per-break queries lift scores above the generic query's 0.67 only for fee breaks (0.748 to 0.758). Other categories
  sit at 0.69 to 0.75 and overlap the fee range (FXR-002 0.745 to 0.747 against FEE-004 0.748 to 0.751).
- No per-break score is below 0.61, so **the floor dropped nothing in either run**. All 33 dropped hits per run were
  dropped by the category match. A floor high enough to separate fee from other breaks (about 0.74) would be tuned on the
  cases, so it was not used.
- The break-specific query does not make the cosine a usable discriminator on this three-document corpus. It makes the
  query carry the category word, which is what the category match then uses.

## 4. Filter kept / dropped per case (collected this time)

Read from the `KNOWLEDGE_CONSULTED` audit rows (counts only), identical in both runs and identical to the min-score 0
probe. Cosine is the range over the three hits.

| Case | Route run 1 | Route run 2 | Root cause run 1 / 2 | Kept / dropped | Cosine |
|---|---|---|---|---|---|
| DAT-001 | ok | ok | ok / ok | 0 / 3 | 0.657 to 0.661 |
| DAT-002 | ok | ok | ok / ok | 3 / 0 | 0.723 to 0.724 |
| DAT-003 | ok | ok | ok / ok | 3 / 0 | 0.702 to 0.703 |
| DUP-001 | ok | ok | ok / ok | 3 / 0 | 0.716 to 0.718 |
| DUP-002 | ok | ok | ok / ok | 3 / 0 | 0.718 to 0.719 |
| DUP-003 | ok | ok | ok / ok | 3 / 0 | 0.713 to 0.715 |
| FEE-001 | ok | ok | ok / ok | 3 / 0 | 0.750 to 0.752 |
| FEE-002 | ok | ok | ok / ok | 3 / 0 | 0.755 to 0.758 |
| FEE-003 | ok | ok | ok / ok | 3 / 0 | 0.756 to 0.757 |
| FEE-004 | ok | ok | ok / ok | 3 / 0 | 0.748 to 0.751 |
| FXR-001 | ok | ok | ok / ok | 0 / 3 | 0.729 to 0.731 |
| FXR-002 | ok | ok | ok / ok | 0 / 3 | 0.744 to 0.747 |
| FXR-003 | miss | miss | ok / ok | 3 / 0 | 0.713 to 0.715 |
| HRD-001 | ok | ok | ok / ok | 3 / 0 | 0.703 to 0.706 |
| HRD-002 | ok | ok | miss / miss | 0 / 3 | 0.706 to 0.711 |
| MIS-001 | miss | miss | ok / ok | 3 / 0 | 0.705 to 0.707 |
| MIS-002 | miss | miss | ok / ok | 3 / 0 | 0.720 to 0.722 |
| MIS-003 | refused (422) | miss | refused / ok | 3 / 0 | 0.697 to 0.698 |
| PAR-001 | ok | ok | ok / ok | 0 / 3 | 0.690 to 0.692 |
| PAR-002 | miss | miss | ok / ok | 0 / 3 | 0.701 to 0.703 |
| PAR-003 | ok | ok | ok / ok | 0 / 3 | 0.692 to 0.693 |
| TIM-001 | ok | ok | ok / ok | 0 / 3 | 0.709 to 0.714 |
| TIM-002 | ok | miss | ok / ok | 0 / 3 | 0.719 to 0.723 |
| TIM-003 | ok | ok | ok / ok | 0 / 3 | 0.716 to 0.720 |
| TIM-004 | ok | ok | ok / ok | 0 / 3 | 0.694 to 0.699 |
| UNK-001 to UNK-003 | ok | ok | ok / ok | no evidence, no lookup | n/a |

Totals per run: 25 lookups, 42 hits kept, 33 dropped, 14 cases with at least one kept hit. The audit `queryKind` was
`BREAK_SPECIFIC` on every row.

What the counts explain (from the logged queries; the model output itself was not inspected):

- **MIS-001 to MIS-003 (kept 3 / 0, still lost):** the break text has no term from the category list, so the query falls
  back to the generic words ("variance fee timing settlement fx duplicate"), no category is found, and by rule the hits
  are kept. The fee procedures go into the prompt and the model routes to manual review. The per-break query cannot help
  where the break gives no category word. This confirms the PQ1.2.4 hypothesis with counts; the fix is a broader term
  list or a model-free category classifier, not a new query.
- **FXR-003 (kept 3 / 0):** same fallback to generic words; it is also a baseline miss.
- **PAR-002, TIM-002 (dropped 3 / 3):** the category was found, procedures dropped, yet the route is still wrong in
  PAR-002 (baseline miss) and in TIM-002 run 2. Dropping the fee text did not make the model route correctly; these are
  model noise or case difficulty, not knowledge.
- **HRD-002 root-cause miss (dropped 3 / 3):** present in both runs with all procedures dropped, so not caused by
  knowledge. Which case is the baseline's single root-cause miss was not re-derived.

## 5. Failure to note

Run 1, MIS-003: the gateway answered `422 SENSITIVITY_BLOCKED` for that call (no spend, no retry). The same case passed
the gateway in run 2. The cause was not investigated (gateway-side sensitivity classification, not the knowledge step).
It is counted as a route and root-cause miss in run 1.

## 6. Stack, corpus, budget

- Keycloak 8190, EKA edge 8080, ai-gateway 8280 on its `real-provider` profile, started from the platform spike stack as
  in PQ1.2.4. The provider key reached the gateway container only (from the platform `.env`); nothing printed. The test
  JVM never held the key.
- Corpus: **not reseeded.** The EKA volume still held the same three fee procedures for tenant-a from PQ1.2.4 (checked
  by listing and a search before the runs), so this is the identical corpus, not a reconstruction.
- Transparent logging proxy (a scratch script outside the repos) between the tests and the EKA edge, to record each
  masked query and the cosine scores; a second scratch poller read the audit rows. Neither changes requests. No code in
  the repos was modified.
- Gateway tenant ceiling set outside the repos. The gateway ledger still held the 0.01450230 USD of PQ1.2.4 in the
  day's window, so the first attempt with a 0.015 ceiling was blocked on every call (`429 BUDGET_BLOCKED`, 25 refusals,
  no spend, no result used). The ceiling was then set to 0.025 USD (about 0.0105 above the existing spend, under the
  0.02 cap) and the runs repeated.
- The stack containers started for this task were stopped afterwards (volumes kept).

## 7. Spend (gateway ledger, tenant-a window)

| Run | Settled calls | USD |
|---|---|---|
| Blocked attempt | 0 | 0 |
| 1 | 24 | 0.00463440 |
| 2 | 25 | 0.00480030 |
| **Total** | **49** | **0.00943470** |

Read as the change in `budget_window_usage.settled_cost_usd` (0.01450230 before, 0.02393700 after) and confirmed by the
sum of `budget_reservation.actual_cost_usd` for the new calls (49 calls, 45378 tokens). Under the 0.02 USD cap. Embeddings
used the test hashing model: no embedding spend.

## 8. Reproduce

Needs the stack above with the gateway on its real-provider profile (ceiling above the day's existing spend), tenant-a
fee procedures in EKA, and approval for a paid run:

```bash
GATEWAY_LIVE_APPROVED=yes EKA_LIVE_BASE_URL=http://localhost:8080 KNOWLEDGE_MIN_SCORE=0.61 \
mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live,eka,rero-live" \
  -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=IntegratedPlatformEvalIT
```

The per-case kept/dropped counts are in the `KNOWLEDGE_CONSULTED` audit rows of the test database (queried while the
run is alive; the Testcontainers database is removed when the JVM exits).

## 9. Honest summary

- The break-specific query did not produce a measurable gain: 23/28 and 22/28 against 21/28, with intervals that all
  contain 24/28. The best reading is "perhaps +1 or +2 timing cases, within noise", not "improved".
- The min-score floor is inert on this corpus: every per-break score is above it. The category match does all the
  filtering, and it is what the break-specific query feeds.
- Still 3 route cases below the no-knowledge baseline, all MIS-001 to MIS-003, because those breaks give no category
  word. Next options (not done, each needs another approved run): widen the term list or classify the category from the
  model-free evidence summary; add non-fee procedures to the corpus so missing-entry and timing breaks have relevant
  guidance; a larger case set so a few cases do not decide the answer.
