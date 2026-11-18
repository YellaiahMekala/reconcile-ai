# PQ1.1.2: per-tenant gateway credentials against a real Keycloak realm (2026-11-18)

Status: **passed, local, free (stub provider).** One run, one machine. Not in CI (tag `gateway`).

## What was feasible

No realm change was needed. The platform realm (`platform/keycloak/realm-platform.json`, imported at Keycloak start)
already holds two service clients, each with a hard-coded `tenant_id` claim: `svc-reconcile-tenant-a` (tenant-a) and
`svc-reconcile-tenant-b` (tenant-b). Their secrets come from the platform `.env` (git-ignored) and reach the test only
through the environment variables `KC_SVC_TENANT_A_SECRET` and `KC_SVC_TENANT_B_SECRET`. Nothing secret is committed.

## Test

`RealGatewayPerTenantIT` (tag `gateway`, skipped unless both variables are set). The app is configured with
`reconcile.gateway.tenants[tenant-a]` and `[tenant-b]` (client ids plus `client-secret-env` names). The test:

1. runs one investigation as tenant-a and one as tenant-b, through the real gateway (spike stack, stub provider);
2. reads the stored gateway invocation id of each job;
3. fetches each id from the gateway ledger with a token of client A and a token of client B.

The gateway scopes ledger rows to the tenant in the token, so the ledger itself shows which client the app used:

| Read | Expected | Result |
|---|---|---|
| tenant-a invocation, client A token | 200 | 200 |
| tenant-b invocation, client B token | 200 | 200 |
| tenant-a invocation, client B token | not 200 | not 200 |
| tenant-b invocation, client A token | not 200 | not 200 |

`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.

## Command

```bash
# stack: bash scripts/spikes/up.sh (platform repo); secrets exported from the platform .env into the shell, not printed
mvn -B -ntp verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway-live" "-Dtest=NONE" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=RealGatewayPerTenantIT"
```

## Limits

- Refusal paths (no client for a tenant, token claiming another tenant) stay covered against the fake gateway in
  `GatewayPerTenantCredentialsIT`; they were not repeated on the real realm.
- The test records only "not 200" for the cross-tenant read, not the exact refusal code.
