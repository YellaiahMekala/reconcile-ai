# PQ1.2.4: route accuracy re-measured with the relevance filter (2026-11-18)

Status: **run 2026-11-18, local, three runs, paid and approved (cap 0.10 USD).** Same 28 synthetic cases, same model
(`gpt-4o-mini` through the real ai-gateway), same test hashing embeddings and same harness as
`q4-3-integrated-eval-2026-11-18.md` and `q4-3-knowledge-ablation-2026-11-18.md`. The only change is the D1.1.1
`KnowledgeRelevanceFilter` (`docs/proof/d1-1-1-relevance-filter.md`), now active with `reconcile.knowledge.min-score` 0.60.

**Result: partly helped, did not recover the baseline.** Route accuracy 21/28 (75.0%) in all three runs, against 24/28
without knowledge and 19/28 with unfiltered knowledge. The filter recovered 2 of the 5 lost cases (TIM-001 and HRD-002);
the three missing-entry cases (MIS-001 to MIS-003) are still sent to manual review.

## 1. Comparison

| Metric | Baseline (direct, no knowledge) | Gateway only | Integrated, unfiltered | Integrated + filter (this work, 3 runs) |
|---|---|---|---|---|
| Route correct | 24/28 (85.7%) | 24/28 (85.7%) | 19/28 (67.9%) | **21/28 (75.0%) in each run** |
| Root cause correct | 27/28 | 27/28 | 27/28 | 27/28 (96.4%) in each run |
| Citations verified | 100% | 96.0% | 100% | 100% |
| Latency p50 / p95 | 2634 / 3555 ms | 2853 / 4063 ms | 2779 / 3825 ms | 3290 / 4319, 3085 / 3776, 3131 / 3866 ms |
| Tokens in / out | 15964 / 2952 | 15951 / 2969 | 24414 / 2989 | 20222 / 2988, 20204 / 2980, 20224 / 3040 |

Prompt tokens fall from about 24400 (unfiltered) to about 20200 per run, so the filter did remove procedures from some
prompts; the no-knowledge run used about 15950.

## 2. Spread and confidence

- The three runs agree exactly on the count (21/28). Per case, runs 1 and 2 had the same route misses; run 3 differed in
  one case (TIM-001 missed, TIM-004 correct). The gateway temperature is low, so repeats mostly reproduce.
- Repeats measure model noise only. They do not widen the sample of cases, so they do not tighten the interval below.
- 95% Wilson interval for 21/28: **56.7% to 87.3%**. The baseline 24/28 (85.7%) lies inside it. With n = 28 this run
  cannot show a difference from the baseline, and it also cannot show that the filter equals the baseline.
- Against unfiltered knowledge (19/28) the gain is 2 cases: too small to call significant.

## 3. Which cases

Route misses with the filter (runs 1 and 2): FXR-003, MIS-001, MIS-002, MIS-003, PAR-002, TIM-002, TIM-004 (7 cases).
Run 3: the same except TIM-001 replaces TIM-004.

| Group | Cases | Reading |
|---|---|---|
| Missed in the baseline too (by elimination from the earlier reports) | FXR-003, PAR-002, TIM-002, TIM-004 | not caused by knowledge |
| Lost to unfiltered knowledge, now correct | TIM-001, HRD-002 (route) | filter helped (TIM-001 flipped back in run 3) |
| Lost to unfiltered knowledge, still lost | MIS-001, MIS-002, MIS-003 | filter did not help |

Likely reason for MIS-001 to MIS-003 (hypothesis, not measured per case): the filter only drops a procedure when it can
find a candidate category in the break's own words. The MIS-001 evidence file says the second receipt "does not appear"
and never uses a keyword from the missing-entry term list, so no category is found and the rule is to keep the hit.
The timing cases mention "cut-off" and "next business day", so a category is found and the fee procedures are dropped.
Per-case `relevanceKept` and `relevanceDropped` counts were not collected in these runs, so this is an inference from
the evidence text, the term list and the outcome pattern.

## 4. Why min-score 0.60 (chosen before running, not tuned on results)

EKA's `scores.cosine` was probed on the live corpus (three fee procedures for tenant-a) with queries unrelated to the
cases:

| Query | Cosine of the fee procedures |
|---|---|
| unrelated (cafeteria menu, marketing guidelines) | 0.43 to 0.52 |
| the generic query reconcile-ai actually sends | 0.67 |
| a direct fee question | 0.79 |

0.60 sits between the highest unrelated score (0.52) and the generic-query score (0.67). It was fixed from these probes
before any case was run and was not changed between runs. Limit: reconcile-ai sends the same generic query for every
break, so the score is nearly constant per corpus and the floor cannot separate one break from another. All the
discriminating work is done by the category match, which is why only the cases with a visible category signal improved.

## 5. Stack and corpus

- Keycloak 8190, EKA edge 8080, ai-gateway 8280 on its `real-provider` profile (OpenAI `gpt-4o-mini`), started from the
  platform spike stack. The provider key reached the gateway container only, from the platform `.env`; nothing printed.
- The gateway tenant cost ceiling was set to 0.06 USD for this task (temporary override outside the repos) as a backstop
  under the 0.10 USD cap.
- **Corpus caveat:** the EKA volume was empty at the start (the earlier corpus was gone). Three fee procedures with the
  golden-path wording were seeded for tenant-a, matching "tenant-a holds only fee procedures, 3 hits" from the earlier
  runs. It is a reconstruction, not the same documents.

## 6. Spend (gateway ledger)

| Run | OK calls | USD |
|---|---|---|
| 1 | 25 | 0.00482610 |
| 2 | 25 | 0.00481860 |
| 3 | 25 | 0.00485760 |
| **Total** | **75** | **0.01450230** |

The ledger was empty of billable calls before run 1 (the free stub runs cost 0), so the total is the cumulative ledger
sum read after run 3. Embeddings used the test hashing model: no embedding spend. Well under the 0.10 USD cap.

## 7. Reproduce

Needs the stack above with the gateway on its real-provider profile, tenant-a fee procedures in EKA and approval for a
paid run:

```bash
# secrets and the provider key come from the environment only
GATEWAY_LIVE_APPROVED=yes EKA_LIVE_BASE_URL=http://localhost:8080 KNOWLEDGE_MIN_SCORE=0.60 \
mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live,eka,rero-live" \
  -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=IntegratedPlatformEvalIT
```

## 8. Honest summary

- The filter is a partial fix on this corpus: +2 cases over unfiltered knowledge, still 3 below the no-knowledge
  baseline, and within noise of both given n = 28.
- Do not claim the route drop is fixed. A keyword-only category signal misses breaks whose evidence uses other words.
- Next options (not done): widen the term list or classify the break category from the model-free evidence summary,
  and add non-fee procedures to the corpus so missing-entry and timing breaks have relevant guidance. Either needs
  another approved run.
