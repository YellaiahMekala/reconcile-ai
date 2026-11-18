# Q4.2 and Q4.5: platform golden path and deliberate failure (2026-11-18)

Status: **run 2026-11-18, passed, local, uncommitted at the time of writing.** One machine, one run of each demo. This is
a paid run: the gateway called OpenAI (`gpt-4o-mini`).

## 1. Stack

| Service | Port | Notes |
|---|---|---|
| Keycloak (platform realm) | 8190 | issues the per-tenant service tokens for both audiences |
| EKA edge | 8080 | edge profile, accepts tokens from the platform realm |
| ai-gateway | 8280 | real-provider profile (OpenAI `gpt-4o-mini`), default tenant cost ceiling 0.50 USD (`platform/compose/golden/gateway.live.yaml`) |
| reconcile-ai | 8085 | `KNOWLEDGE_ENABLED` and `GATEWAY_ENABLED`, per-tenant clients, `mock-llm` profile |
| reconcile-ai Postgres | 5433 | moved off 5432 because EKA's database holds 5432 (`platform/compose/golden/reconcile-db.override.yaml`) |

reconcile-ai runs the `mock-llm` profile, so it has no direct chat model: the gateway is the only chat path.

## 2. Reproduce

From the platform repo root, with the git-ignored `.env` filled in (the provider key lives there only):

```bash
bash scripts/golden/up.sh                      # starts the stack; PAID once a demo runs
pwsh scripts/golden/golden-path.ps1            # demo 1: happy path
pwsh scripts/golden/golden-path.ps1 -EkaDown   # demo 2: EKA edge stopped on purpose
bash scripts/golden/down.sh
```

## 3. First attempt: refused by the gateway (a finding)

The first run did not reach the model. The gateway answered **422 `SENSITIVITY_BLOCKED`**, `data_sensitivity`
`RESTRICTED`, detector `PAYMENT_CARD`, cost 0.

- **Cause:** the break reference `BRK-GOLD-20261118090525` contains a 14-digit timestamp that passes the Luhn check. The
  gateway's card detector only requires that the digit run is not next to another digit. reconcile-ai's masker skipped
  digit runs glued to a letter or a dash, so the run went out unmasked.
- **It failed safe:** nothing reached the provider, cost was 0, and the break went to manual review.
- **Fix in `PiiMasker`:** an extra pass masks a contiguous Luhn-valid run of 13 to 19 digits even inside a reference.
  UUIDs cannot match, because the longest UUID segment is 12 digits. Two new unit tests are in `PiiMaskerTest`.
- **Known remaining gap:** cards split by dots, or digits spread across UUID-like dash groups, are not masked by
  reconcile-ai. The gateway still refuses those. That is fail-safe, not a leak.
- **Full build after the fix:** 168 unit / 193 integration, 0 failures.

## 4. Demo 1: happy path (passed)

| Step | Result |
|---|---|
| Knowledge | `OK`, 3 procedures consulted |
| Gateway call | 200; ledger status `OK`, executionCertainty `EXECUTED`, model `openai-gpt-4o-mini`, usageSource `PROVIDER_REPORTED`, actualCostUsd 0.00023460 |
| Proposal | `FEE_OR_CHARGE`, confidence 0.900, citationsValid true, no `kb:` reference cited, route `L1_APPROVAL` |
| Approval | a different person (`APPROVER_L1`) approved; break `RESOLVED` |
| Audit | chain valid, 13 events |

## 5. Demo 2: deliberate failure, EKA down (passed)

The EKA edge container was stopped before the job (`-EkaDown`) and restarted afterwards.

| Step | Result |
|---|---|
| Job | `SUCCEEDED` with knowledgeOutcome `UNAVAILABLE`, reason `NETWORK` (fail-open default, degraded context visible) |
| Gateway call | 200, `EXECUTED`, actualCostUsd 0.00018810 |
| Proposal | `FEE_OR_CHARGE` 0.900, route `L1_APPROVAL`, approved, break `RESOLVED` |
| Audit | chain valid, 20 events |

## 6. Cost

| Item | Cost (USD) |
|---|---|
| First attempt (refused, nothing sent) | 0 |
| Demo 1 gateway call | 0.00023460 |
| Demo 2 gateway call | 0.00018810 |
| Both demos | 0.00042270 |

Cost figures are from the gateway ledger (`PROVIDER_REPORTED` usage). Today's total across all live work is in
`q4-3-integrated-eval-2026-11-18.md`.

## 7. What this does and does not show

- It shows the integrated path works end to end once on one laptop, and that an unavailable EKA degrades visibly instead
  of failing the job.
- It does not show capacity, repeatability across runs, or behaviour under a provider outage.
- EKA hits are "procedure consulted", never evidence; the proposal cited no `kb:` reference in either demo.
