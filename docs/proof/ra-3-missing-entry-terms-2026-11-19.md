# RA-3: missing-entry phrasing in the category term list (2026-11-19)

Status: **code change made and unit-tested; paid re-measure NOT run** (the provider key was not available in the
agent environment, so no model call was made; spend 0.00 USD).

## Cause (read from code and the D1.2.12 audit counts, no paid call)

MIS-001 to MIS-003 still lost route accuracy after D1.2.12 because their evidence never uses a word from the
`MISSING_ENTRY` term list (`missing`, `not recorded`, `not posted`, ...). The evidence says "No credit for INV-30402
appears anywhere", "no matching credit exists" and "has no counterpart on the bank side". So
`KnowledgeRelevanceFilter.candidateCategories` found no category, `BreakKnowledgeQuery` fell back to the generic hint
words, and the filter's conservative rule kept all 3 fee procedures (audit: kept 3 / dropped 0). The model then saw fee
guidance on a missing-entry break and routed to manual review.

## Change

`KnowledgeRelevanceFilter.TERMS` for `MISSING_ENTRY` gained four phrases: `no matching`, `no counterpart`, `no credit`,
`no debit`. Same list feeds the break-specific query, so those breaks now also get the "missing entry" hint. Nothing
else changed (min-score, category rule, defaults, auto-resolve off).

Side-effect check (free): across the 28 eval evidence files plus the hard set, only `mis-001..003` and `hrd-006`
(a "no matching entry ... for this refund" note) contain the new phrases. No other evaluated case changes category set.

## Verified

- New unit test `missingEntryPhrasingWithoutTheWordMissingIsRecognised` (the three evidence phrasings): a fee procedure is
  dropped and the missing-entry procedure kept.
- `KnowledgeRelevanceFilterTest` 10/10, `BreakKnowledgeQueryTest` 6/6, full unit suite 218 tests, 0 failures.

## Not measured

Expected effect: MIS-001..003 now get 0 kept / 3 dropped fee procedures, i.e. the no-knowledge prompt, which routed them
correctly in the baseline. That is a prediction from the filter logic, not a measurement. To measure, run the D1.2.12
reproduce command (`docs/proof/d1-2-12-break-query-remeasure-2026-11-18.md`, section 8) with the provider key available;
expected cost about 0.005 USD per 28-case run. With n = 28 (3 cases affected) a gain of up to 3 route cases is
distinguishable from the baseline only as "back to baseline", not as an improvement over it.
