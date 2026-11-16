# Q2.7 live provider run: evidence (template)

Copy to `q2-7-gateway-live-<YYYY-MM-DD>.md`, replace every `<...>`, delete this line.

## Configuration

| Item | Value |
|---|---|
| Date (UTC) | `<YYYY-MM-DDThh:mm:ssZ>` |
| Approval | `<who approved the spend and where: PR comment / register row>` |
| reconcile-ai commit | `<sha>` |
| ai-gateway commit and image | `<sha>`, `<image tag>` |
| Keycloak realm | `platform` (realm export commit `<sha>`), client `svc-reconcile-tenant-a`, scope `aud-ai-gateway` |
| Gateway provider and model key | `<provider>`, `<modelKey as the ledger reports it>` |
| Tenant budget set on the gateway | `<amount USD>` |
| Command | `mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway" "-Dtest=NONE" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=GatewayLiveProviderIT"` |
| Test result | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` |

## Evidence block (pasted from `target/q2-7-gateway-live-evidence.md`)

<paste the two tables the test printed: job fields, then one row per attempt with the ledger lookup result>

## Cross-check against the gateway's own ledger

| Field | reconcile-ai (`gateway_call` / `llm_call`) | ai-gateway (`GET /api/v1/invocations/{id}`) | Match |
|---|---|---|---|
| invocationId | `<uuid>` | `<uuid>` | yes / no |
| status | `<SUCCESS / REFUSED / UNKNOWN>` | `<OK / ...>` | |
| executionCertainty | n/a | `<EXECUTED / NOT_EXECUTED / UNKNOWN>` | |
| input tokens | `<n>` | `<n>` | |
| output tokens | `<n>` | `<n>` | |
| usageSource | n/a | `<PROVIDER_REPORTED / ESTIMATED>` | |
| actualCostUsd | n/a | `<0.000nnn>` | |

## Result in one sentence

`<for example: one request, one ledger row, EXECUTED, 0.0002 USD, job SUCCEEDED with a cited TIMING_DIFFERENCE proposal routed to L2_APPROVAL>`

## Not shown by this run

- Answer quality: one investigation, one model; see the evaluation harness for quality numbers.
- Budget and policy refusals on the real gateway: proven against the fake gateway (`GatewayBudgetIT`, `GatewaySensitiveDataIT`) and in the gateway's suite.
