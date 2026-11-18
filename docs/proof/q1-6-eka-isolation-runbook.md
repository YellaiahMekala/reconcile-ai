# Q1.6 and Q1.7 runbook: tenant isolation and concurrent lookups against the real EKA

Status: **run 2026-11-18, 3/3 passed** (local, one run). This page is the procedure and the result table. `RealEkaIsolationIT` (tag `eka`) is excluded from
the default build and from CI, and skips itself unless `EKA_LIVE_BASE_URL` is set. No model is called and nothing is
billed: the test uses reconcile-ai's own token and search code against a local EKA and the platform realm.

## 1. What the run proves

| Claim | How the test shows it |
|---|---|
| reconcile-ai's per-tenant token passes the EKA edge, and the `tenant_id` claim check passes with the real realm | tenant A uploads a synthetic procedure and finds it through `EkaKnowledgeClient` (outcome `OK`) |
| **Q1.6 (blocking):** tenant B sees nothing of tenant A | the exact marker text searched as tenant B gives `EMPTY`; `GET /api/v1/documents/{id}` as B is 404; B's document list does not contain the id |
| **Q1.7:** ten concurrent lookups for one tenant all return a typed outcome | ten parallel searches; each is `OK` or `UNAVAILABLE` (no exception, no hit on `UNAVAILABLE`); the outcome and latency of each is printed |

It does not prove capacity, ranking quality or anything about the agent modes (they do not use knowledge).

## 2. Prerequisites

1. **Platform Keycloak realm on port 8190.** From the platform repo: `bash platform/scripts/spikes/up.sh` (it also starts
   the gateway stub, which this run does not need). The realm has the clients `svc-reconcile-tenant-a` and
   `svc-reconcile-tenant-b`, whose tokens carry `tenant_id` `tenant-a` and `tenant-b`.
2. **EKA edge profile on port 8080**, accepting tokens from that realm (as in spike S-2):

   ```bash
   # EKA repo
   EKA_EDGE_OIDC_ISSUER=http://localhost:8190/realms/platform \
   EKA_EDGE_OIDC_JWK_SET_URI=http://host.docker.internal:8190/realms/platform/protocol/openid-connect/certs \
   docker compose -p platform-eka --profile edge up --build
   ```

   The core must be in `gateway` auth mode and the ingestion worker must be running, or the upload never becomes `ready`.
3. **Tenant client secrets as environment variables**, taken from the realm export. Never write them to a file in this
   repo.

## 3. Command

```powershell
$env:EKA_LIVE_BASE_URL = "http://localhost:8080"
$env:KNOWLEDGE_TOKEN_URI = "http://localhost:8190/realms/platform/protocol/openid-connect/token"
$env:KC_SVC_TENANT_A_SECRET = "<secret of svc-reconcile-tenant-a>"
$env:KC_SVC_TENANT_B_SECRET = "<secret of svc-reconcile-tenant-b>"
mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live" -Dit.test=RealEkaIsolationIT
```

The test uploads one synthetic markdown file as tenant A, waits up to 60 s for `ready`, runs the three tests, and deletes
the file afterwards.

## 4. What pass means

- **Q1.6:** `tenantBSeesNothingOfTenantA` passes. This is the blocking test. If it fails, do not enable
  `KNOWLEDGE_ENABLED` anywhere and record the failure here.
- `tenantAFindsItsProcedureThroughTheReconcileClient` passes, so a Q1.6 pass is not an empty result caused by a broken
  token or an unready document.
- **Q1.7:** `tenConcurrentLookupsAllReturnATypedOutcome` passes. Copy the printed line `Q1.7 concurrent lookups: [...]`
  into the table. Record the outcome mix and latencies as observed on one machine; do not turn them into a capacity
  claim. An `UNAVAILABLE` with reason `RATE_LIMITED` or similar is a result to record, not a failure of the test.

A skipped run (no `EKA_LIVE_BASE_URL`) is not a pass.

## 5. Results

| Date | reconcile-ai commit | EKA commit | Q1.6 (A finds, B empty, B 404) | Q1.7 printed line | Notes |
|---|---|---|---|---|---|
| 2026-11-18 | uncommitted | `3ae3100` (edge profile) | Pass: tenant-a found its procedure (`OK`); tenant-b searching the exact marker text got `EMPTY`; GET on tenant-a's document id as tenant-b was 404; the listing did not contain it | `OK` x10; latencies 199, 322, 330, 343, 356, 367, 375, 365, 374, 380 ms | 3/3 tests passed. Platform Keycloak 26.3 on 8190, reconcile-ai's own `TenantServiceTokens` and `EkaKnowledgeClient`. One run on one laptop, not a capacity claim. No model calls, no cost |

After a run, update `platform/docs/STATUS_TRACKER.md` (Q1.6, Q1.7) and add a row to `platform/evidence/EVIDENCE_REGISTER.md`.
