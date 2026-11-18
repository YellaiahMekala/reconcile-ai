# PQ1.1.1: RealGatewayIT against the local spike stack (2026-11-18)

Status: **passed, local, free (stub provider, cost 0).** One run on one machine. Not in CI (tag `gateway`).

## Stack

Started with `bash scripts/spikes/up.sh` from the platform repo: Keycloak (realm `platform`) on 8190, ai-gateway on 8280
with the stub provider, EKA edge on 8080. The client secret came from the platform `.env` into the environment variable
`GATEWAY_CLIENT_SECRET` for the Maven process only; it is not in any file here.

## Command

```bash
mvn -B -ntp verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway-live" "-Dtest=NONE" \
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=RealGatewayIT"
```

## Result

`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Evidence block written by the test
(`target/gateway-spike-evidence.md`, 2026-11-18T11:53:47Z):

| field | value |
|---|---|
| gateway | http://localhost:8280 |
| tenant | tenant-a |
| jobId | 1a7443f8-08ad-403a-b992-ff6f42c84742 |
| job status / reason | FAILED / MODEL_OUTPUT_NOT_VALID_JSON |

| attempt | outcome | http | invocationId | gateway status | in | out | ms | ledger |
|---|---|---|---|---|---|---|---|---|
| 1 | SUCCESS | 200 | 8448dc37-901e-4d7f-b072-70531bf0f2a5 | OK | 296 | 9 | 2042 | 200 OK EXECUTED cost=0.0 |

`llm_call` rows: model `ai-gateway`, 296 in, 9 out, outcome OK (same counts as the gateway).

## Reading

- The realm issued a token with the gateway audience, the gateway accepted the request shape and idempotency key, and the
  invocation id resolved on the gateway ledger under our token (200).
- The job ends `FAILED / MODEL_OUTPUT_NOT_VALID_JSON` by design: the stub provider returns "Stub response ..." text, not a
  proposal. The assertions are on the link to the ledger, not on the answer.
- Says nothing about answer quality, budgets or policy on the real gateway.
