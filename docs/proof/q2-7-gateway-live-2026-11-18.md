# Q2.7 live provider run (2026-11-18)

Run once with approval, `GatewayLiveProviderIT` (tag `gateway-live`), against the real ai-gateway on its real-provider
profile. **Passed.** Procedure: `q2-7-gateway-live-runbook.md`. The block below is the evidence file the test generated
(`target/q2-7-gateway-live-evidence.md`), generated 2026-11-18T03:57:27Z.

| field | value |
|---|---|
| gateway | http://localhost:8280 |
| tenant | tenant-a |
| jobId | 223e09d3-2cd8-44e0-ac60-a1ec307167fe |
| job status / reason | SUCCEEDED / null |
| started (UTC) | 2026-11-18T03:57:22.234428100Z |
| finished (UTC) | 2026-11-18T03:57:27.033737400Z |

| attempt | key | outcome | http | code | invocationId | gateway status | in | out | ms | ledger |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | recon:223e09d3-2cd8-44e0-ac60-a1ec307167fe:investigate:1 | SUCCESS | 200 | null | 1f47fbb6-1ee0-4ba3-8ddd-d9fbc2fd6bbb | OK | 719 | 111 | 3919 | 200 OK EXECUTED cost=1.7445E-4 |

`llm_call` rows: `[{model=ai-gateway, prompt_tokens=719, completion_tokens=111, outcome=OK}]`

## Reading

- The service token for the gateway audience was accepted (no 401) and the request shape and idempotency key were
  accepted.
- One attempt, no retry. The gateway's invocation id resolves on its ledger under our token: 200, `OK`, `EXECUTED`,
  cost 0.00017445 USD.
- Our `llm_call` row (model `ai-gateway`) has the same token counts as the gateway (719 in, 111 out).
- One investigation: this says nothing about answer quality, budgets or policy on the real gateway.
