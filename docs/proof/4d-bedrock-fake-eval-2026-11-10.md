# 4D.4 proof, local edition: the eval set through the Bedrock adapter (2026-11-10)

`BedrockFakeEvalIT` runs all 28 labeled cases through the **real Amazon Bedrock adapter** (AWS SDK v2, Converse wire
format, usage and cost recording, model label) against a fake Converse endpoint in the test JVM. No AWS account, no cost.
The fake answers each request by applying the deterministic mock-llm rules to the prompt text it actually receives, so
the run measures **adapter fidelity**: if the adapter dropped prompt text, mis-parsed the answer or lost citations, the
scores would fall below the mock-llm floor. Real model quality on Bedrock is a separate, still-pending live run.

## Result: identical to the direct mock run

```
                                       mock-llm     bedrock-fake       change
Job success rate                         100.0%           100.0%      0.0 pts
Root-cause accuracy                       42.9%            42.9%      0.0 pts
Route accuracy                            42.9%            42.9%      0.0 pts
Citations verified                       100.0%           100.0%      0.0 pts
Required evidence cited                   33.3%            33.3%      0.0 pts
Latency p50 (ms)                            321              288          -33
Latency p95 (ms)                            599              324         -275
Cost per case (USD)                    0.000000         0.000000            0

Cases that differ:
  none
```

Every case that reached a model is recorded in `llm_call` with model `amazon.nova-lite-v1:0` and prompt tokens > 0; the
cases with no call were served from the semantic cache, exactly as in the mock-llm run. Report:
`docs/proof/eval/report-bedrock-fake-2026-11-10.md` (from `target/eval/report-bedrock-fake.md`).

## Provider comparison so far

| Run | Chat model | Root cause | Route | Citations verified | p50 | Cost per case | What it proves |
|---|---|---|---|---|---|---|---|
| mock-llm | deterministic rules | 42.9% | 42.9% | 100% | 0.3 s | $0 | pipeline floor |
| bedrock-fake | same rules via the Bedrock adapter | 42.9% | 42.9% | 100% | 0.3 s | $0 | the adapter changes nothing |
| openai single call (live, 2026-11-09) | gpt-4o-mini | 96.4% | 89.3% | 96.0% | 2.5 s | $0.00015 | real model quality |
| openai agent (live, 2026-11-09) | gpt-4o-mini, 4 tools | 100% | 57.1% | 100% | 9.5 s | $0.0012 | agent trade-off |
| bedrock live | amazon.nova-lite-v1:0 | pending | pending | pending | pending | pending | needs AWS access (`BEDROCK_LIVE=true`) |

The last row is the only part of 4D.4 that still needs AWS credentials. It is a one-command run
(`mvn verify "-Dfailsafe.excludedGroups=openai,ollama" "-Dit.test=BedrockEvalIT"`) and costs cents.
