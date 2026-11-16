# Q2.7 runbook: one approved live run through ai-gateway to a real provider

Status: **not run**. This page is the procedure and the evidence format. Nothing here runs in CI, and
`GatewayLiveProviderIT` refuses to start unless two environment variables say so (section 3). The run costs money:
one chat completion plus at most one transport retry under the same idempotency key.

Before this run, do the free one (section 2). It proves every integration point except the provider bill.

## 1. What the run proves

| Claim | Evidence it produces |
|---|---|
| reconcile-ai obtains a service token for the gateway audience from the platform realm | token form sent with `scope=aud-ai-gateway`; the gateway accepts it (no 401) |
| the request shape and idempotency key are accepted by the real gateway | one `gateway_call` row per attempt, `http_status` 200 or a typed refusal |
| the gateway's invocation id links our job to its ledger | `GET /api/v1/invocations/{id}` returns 200 with the same id, under our token |
| the gateway ran a real provider and billed the tenant | ledger row `executionCertainty=EXECUTED`, `usageSource=PROVIDER_REPORTED`, `actualCostUsd` > 0 |
| cost reporting on our side matches | `llm_call` row, model `ai-gateway`, prompt and completion tokens equal to the gateway's |

What it does not prove: answer quality (one investigation), and anything about budgets or policy on the real
gateway. Those are covered against the fake gateway (`GatewayBudgetIT`, `GatewaySensitiveDataIT`) and in the gateway's
own suite.

## 2. The free rehearsal: spike gateway, stub provider

```bash
# platform repo
bash platform/scripts/spikes/up.sh          # Keycloak 8190 (realm platform), ai-gateway 8280 with the STUB provider
```

```powershell
# reconcile-ai repo. The client secret comes from the realm export; never put it in a file in this repo.
$env:GATEWAY_CLIENT_SECRET = "<secret of svc-reconcile-tenant-a>"
mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway-live" "-Dtest=NONE" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=RealGatewayIT"
```

Defaults the test uses (override with the same-named environment variables): `GATEWAY_BASE_URL=http://localhost:8280`,
`GATEWAY_TOKEN_URI=http://localhost:8190/realms/platform/protocol/openid-connect/token`,
`GATEWAY_CLIENT_ID=svc-reconcile-tenant-a`, `GATEWAY_TOKEN_SCOPE=aud-ai-gateway`.

Expected: the test passes and writes `target/gateway-spike-evidence.md`. The job itself will usually end `FAILED` with
`MODEL_OUTPUT_NOT_VALID_JSON`, because the stub provider does not return a proposal; the assertions are on the link
to the ledger, not on the answer. Copy the evidence block into `docs/proof/` and add a register row.

## 3. The paid run

1. **Approve the spend** in writing (a line in the PR or the register row is enough). Expected cost: one small chat
   completion; set the gateway's tenant budget for `tenant-a` to a few cents so a mistake cannot run away.
2. **Start the gateway with a real provider.** This is gateway configuration, not reconcile-ai configuration: the
   provider key goes into the gateway's environment only. reconcile-ai never holds a provider key on this route.
3. **Set the two gates** in the shell that runs Maven, and nothing else:

   ```powershell
   $env:GATEWAY_CLIENT_SECRET = "<secret of svc-reconcile-tenant-a>"
   $env:GATEWAY_LIVE_APPROVED = "yes"
   ```

4. **Run exactly once:**

   ```powershell
   mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway" "-Dtest=NONE" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=GatewayLiveProviderIT"
   ```

5. **Unset the gates** (`Remove-Item Env:GATEWAY_LIVE_APPROVED`) before doing anything else.
6. **Record the evidence.** The test writes `target/q2-7-gateway-live-evidence.md`. Copy it into
   `docs/proof/q2-7-gateway-live-<date>.md` using `q2-7-gateway-live-evidence-template.md`, fill in the gateway commit
   and provider model from the gateway's own log, and redact nothing except secrets (there are none in the block).
7. **Register row.** Add `E-xx` to `platform/evidence/EVIDENCE_REGISTER.md` with status **local live run, not in CI**
   and the cost from the ledger row.

## 4. If it fails

| Symptom | Where to look |
|---|---|
| test skipped | one of the two environment variables is missing; the test prints nothing and reports 1 skipped |
| token endpoint not 200 | realm or client id wrong; try the `s3.sh` spike script in the platform repo |
| `gateway_call` row `REFUSED 401 UNAUTHENTICATED` | the token has no `aud=ai-gateway`: the scope was not sent or the realm lacks the `aud-ai-gateway` client scope |
| `REFUSED 429 BUDGET_BLOCKED` | the tenant budget is zero on the gateway; the run cost nothing, which is also evidence |
| `UNKNOWN 504 UNKNOWN_OUTCOME` | the provider timed out after the request left; the job is now blocked until reconciled (Q2.3). Check the ledger row's `executionCertainty` and, if it ran, reconcile with its invocation id via `POST /api/v1/investigations/{jobId}/reconcile` as a second person |
| ledger GET 404 for an id we stored | tenant mismatch between the token used by the app and the one in the test, or the gateway recorded no row (auth or schema rejection). Compare with the gateway's `invocation` table |
