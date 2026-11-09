# 4A.4 and 4B.6: live OpenAI evaluation, single-call vs agent

Run: 2026-11-09, `OpenAiInvestigationEvalIT` and `OpenAiAgentEvalIT`, chat model `gpt-4o-mini` (reported as `gpt-4o-mini-2024-07-18`), temperature 0, 28 labelled synthetic cases.
Embeddings are the test hashing model, so only the chat model is real and the dense retrieval leg is not semantic.
The per-case reports are written to `target/eval/` (generated, not committed). Compare them with `scripts/eval-compare.ps1`.

| Metric | Single call | Agent (LangChain4j, 4 read-only tools) |
|---|---|---|
| Jobs that finished with a proposal | 100% | 100% |
| Root-cause accuracy | 96.4% (27/28) | 100% (28/28) |
| Route accuracy | 89.3% | 57.1% |
| Cause-claiming answers whose citations verified | 96.0% | 100% |
| Required evidence cited | 100% | 100% |
| Latency p50 / p95 | 2.5 s / 3.3 s | 9.5 s / 13.4 s |
| Cost per case | $0.00015 | $0.0012 (about 8x) |

## What the agent run found first: a real bug

The first agent run scored 28.6% root cause. The tool trace in the report showed every `searchEvidence` call returning "No matching evidence". The single-call mode always searches with a rich query built from the break: reference, amounts, currency and cause words. The model writes its own short query instead, often numbers copied from the break summary that never appear in the files.

Fix: when the model's query finds nothing, the search tool retries once with the model's words plus the same break context query, and tells the model these are the closest passages, not exact matches. The tenant and break still come only from the job. `AgentSearchQueryIT` keeps the failing query as a regression test and checks another tenant still gets nothing. After the fix, root cause went from 28.6% to 100%.

The debug log of the rerun shows the model searching with single words ("fee", "timing", "settlement", "duplicate"), so almost every search used the widened query. Much of the agent's evidence therefore comes from the fallback, not from the model's own searching.

## Reading the route gap

Every agent route miss except two is LOW_CONFIDENCE: right cause, but the model gave a confidence below the threshold, so the policy gate sent the break to manual review. This fails safe, since nothing wrong reached an approver. The two remaining misses (FXR-003, PAR-002) go to L1 where the label says L2, in both modes, and point to the amount bands in the labels or the policy.

## Conclusion

On this data, the agent is not worth its cost: equal or slightly better cause accuracy, more manual reviews, 3.7x latency and 8x cost. Single call stays the default and agent mode stays off unless enabled. The agent earns its place only where evidence is too large or varied to put into one prompt. These 28 short files are not that case.

28 cases is a small synthetic set written by the project author. It shows that both pipelines work with a real model and that the safety net behaves as designed. It is not a benchmark, and the percentages carry wide uncertainty.
