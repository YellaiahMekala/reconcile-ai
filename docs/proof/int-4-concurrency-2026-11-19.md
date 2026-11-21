# INT-4: concurrent investigations through ai-gateway and EKA (2026-11-19)

Status: **run 2026-11-19, local, free (stub model, no paid call), uncommitted at the time of writing.** One laptop, one
run of each scenario, a throwaway stack. These are behaviour and rough-latency figures for this machine, not a capacity
claim. CI run id: pending push (the run needs Docker and three sibling repos, so it is not in CI).

## 1. What was proven

N parallel investigations from reconcile-ai, each with its own break and evidence, all released at once, going through
the real EKA edge (knowledge pre-step) and the real ai-gateway (stub provider). Questions and answers:

| Question | Answer |
|---|---|
| Did it crash under N = 10 and N = 25? | No. Every request got a defined answer (202, or 429 with `Retry-After`); every accepted job reached `SUCCEEDED` or `FAILED` (never stuck). |
| Did anything resolve a break by itself? | No. Every investigated break ended `MANUAL_REVIEW_REQUIRED` (policy route `MANUAL_REVIEW` for all 95 proposals in runs A to E2); none is `RESOLVED`. Auto-resolve stayed off. Audit chains verified valid (tenant-a 389 events, tenant-b 292 events). |
| Where do 429s come from, and how does reconcile-ai degrade? | Three places, section 4: its own start limiter, EKA's per-user limiter, the gateway's tenant limits. Each was reached on purpose and each degraded safely. |
| Timeouts? | EKA unresponsive (core paused): every lookup `UNAVAILABLE / TIMEOUT` at the 5 s limit, then `CIRCUIT_OPEN` (no request sent, 625 ms jobs). Fail-open jobs still `SUCCEEDED` (to manual review). Fail-closed jobs `FAILED / KNOWLEDGE_UNAVAILABLE` (retryable), no model call. |
| Payload or secret leaks in logs? | None found (section 6). |

No product bug was found in the first run, so no code was changed then. The follow-up (sections 8 to 14, same day) added one
small feature to reconcile-ai: a bounded retry of an EKA lookup on HTTP 429 only (section 9). EKA and ai-gateway were not changed.

## 2. Stack (own project names and ports, torn down afterwards)

| Service | Port | Notes |
|---|---|---|
| Keycloak, platform realm | 8590 | project `platform-int4`, per-tenant service clients, audiences `eka-api` and `ai-gateway` |
| ai-gateway | 8580 | project `platform-int4-gw`, stub provider, defaults (tenant rate 20 rps, burst 40, tenant concurrency cap) |
| EKA edge + core + worker + Postgres | 8680 / 5632 | project `platform-int4-eka`, edge profile, defaults (per-user burst 20, 5 per second, in memory) |
| reconcile-ai | 8585 | built from the working tree, `mock-llm` profile, `GATEWAY_ENABLED` and `KNOWLEDGE_ENABLED`, per-tenant clients |
| reconcile-ai Postgres | 5633 | `pgvector/pgvector:pg16`, throwaway credentials |
| Fake embeddings | 8599 | `scripts/int4/fake_embeddings.py`: reconcile-ai's embeddings are always OpenAI, so `SPRING_AI_OPENAI_BASE_URL` points at a local deterministic stand-in with a dummy key. No paid call is possible. |

reconcile-ai has no direct chat model on this route: the only chat path is the gateway stub. The stub normally returns
plain text (which ends `FAILED / MODEL_OUTPUT_NOT_VALID_JSON`, see PQ1.1.1). The driver puts a
`[[stub:reply=<proposal JSON>]]` directive in the analyst note, so the stub answers with a valid proposal and the whole
pipeline (policy gate, route, break status) is exercised. The proposal has no citations, so it routes to `MANUAL_REVIEW`.
One synthetic procedure document was seeded in EKA for each tenant, so lookups return hits (`OK`).

Files (platform repo): `compose/int4/*.yaml`, `scripts/int4/start_app.sh`, `load.py`, `burst.py`, `leak.py`,
`fake_embeddings.py`, `down.sh`. Raw per-job results: `int-4-raw/run-*.json` (job ids and outcomes only).

## 3. Results

Latency is from the POST that starts the investigation to the job's terminal status, measured by the driver (includes
the wait in reconcile-ai's executor queue). "Wide" means `core-pool-size = max-pool-size = 25` and the start limiter
raised to 100, so all 25 jobs run at once and reach EKA and the gateway together; the default pool is 2 to 4 threads
with a 50-slot queue.

| Run | Config | N | Start HTTP | Jobs | Knowledge | e2e p50 / p95 / max (ms) | Wall (ms) |
|---|---|---|---|---|---|---|---|
| A | default, tenant-a | 10 | 202 x 10 | 10 SUCCEEDED | OK x 10 | 3398 / 5188 / 5300 | 5304 |
| B | default, tenant-b | 25 | 202 x 20, **429 x 5** | 20 SUCCEEDED | OK x 20 | 3724 / 5940 / 5995 | 6002 |
| C | wide, fail-open, tenant-a | 25 | 202 x 25 | 25 SUCCEEDED | OK x 23, **UNAVAILABLE x 2 (RATE_LIMITED)** | 6512 / 7662 / 7875 | 7880 |
| D | wide, **fail-closed**, tenant-b | 25 | 202 x 25 | 22 SUCCEEDED, **3 FAILED (KNOWLEDGE_UNAVAILABLE)**; re-run: 3 of 3 SUCCEEDED | OK x 22; UNAVAILABLE x 3 (RATE_LIMITED x 2, TIMEOUT x 1) | 7541 / 8255 / 8480 | 8488 |
| E | wide, fail-open, EKA core paused | 10 | 202 x 10 | 10 SUCCEEDED | UNAVAILABLE x 10 (TIMEOUT) | 6414 / 7405 / 7436 | 7441 |
| E2 | same app, 5 more right after E | 5 | 202 x 5 | 5 SUCCEEDED | UNAVAILABLE x 5 (CIRCUIT_OPEN) | 625 / 675 / 677 | 680 |

Success rate (job reached a terminal state with a proposal on the manual-review route): A 10/10, C 25/25, E 10/10,
E2 5/5; B 20/20 of the accepted starts (5 refused at the door); D 22/25 first pass, 25/25 after one re-run.
Start POST latency (accept only): p50 166 to 572 ms, p95 290 to 1099 ms at N = 10 to 25 (D worst, max 1250 ms).
A first N = 10 run before the EKA documents were seeded (knowledge `EMPTY`, 10/10 SUCCEEDED, p50 2390, p95 3824 ms) is
not in the table.

Why the default-pool runs are not faster per job: with 2 to 4 workers the 10 to 25 jobs queue in the executor (queue
wait p95 3.9 s in A and 5.1 s in B), while the wide runs spend the time inside the jobs (p50 about 5.7 s in C) because
25 lookups and 25 model calls hit the EKA core (one CPU-bound embedding process) and the gateway at the same moment.

## 4. Where the 429s are, and what reconcile-ai does

| Source | Observed | reconcile-ai behaviour |
|---|---|---|
| reconcile-ai start limiter (per tenant, burst 20, 20 per minute) | B: the 21st to 25th start got 429, `Retry-After: 3` | Refused before any work; the 5 breaks stayed `READY_TO_INVESTIGATE` (not stuck, not failed). The caller retries after the header. |
| EKA edge per-user limiter (burst 20, 5 per second) | Direct burst of 40 parallel searches: 21 x 200, 19 x 429; of 60: 21 x 200, 39 x 429. Every 429 has `Retry-After: 1` and a problem+json body (`urn:eka:problem:rate-limited`) with no query text. Through reconcile-ai (C, D): 2 of 25 lookups each | Lookup recorded as `UNAVAILABLE / RATE_LIMITED`, not retried (knowledge is advisory). Fail-open (default): the investigation continues with degraded context, visible on the job. Fail-closed (`KNOWLEDGE_FAIL_CLOSED=true`): the job fails retryably with `KNOWLEDGE_UNAVAILABLE` and makes no model call; a later re-run succeeded 3 of 3. |
| ai-gateway tenant limits (rate 20 rps burst 40; concurrency cap) | Direct burst of 40: 11 x 200, 29 x 429 `TENANT_CONCURRENCY_LIMIT`; of 60: 42 x 200, 18 x 429 `RATE_LIMITED`. Both say "Nothing was sent; retry with the same Idempotency-Key". Through reconcile-ai (C): 2 of 25 calls got 429 | The adapter retried once under the same idempotency key and both succeeded (gateway calls table: attempt 1 `REFUSED / 429`, attempt 2 `SUCCESS / 200`). |
| EKA slow under load | D: 1 of 25 lookups exceeded the 5 s client timeout (`TIMEOUT`) when 25 searches queued on the core | Same as the 429 case: fail-open continues, fail-closed fails retryably. |
| EKA core not answering (E) | All 10 lookups `TIMEOUT` (jobs about 6.4 s), then the breaker opened: the next 5 were `CIRCUIT_OPEN` with no request sent (jobs 0.6 s) | Fail-open: all jobs `SUCCEEDED` to manual review. The breaker (window 10, 50 percent, open 30 s) protects the pool from 5 s waits. |

Rate-limit direct bursts do not touch reconcile-ai. Burst figures are per laptop and per default setting; they describe
the limiter shapes (EKA 20 + 5 per second in memory per edge instance, ADR-025), not a recommended limit.

## 5. Findings and limits

- **Fail-open is the default and is the right one for an advisory lookup**, but under a burst a share of investigations
  (8 percent in C, 12 percent in D) run without the procedure context. It is visible per job (`knowledgeOutcome`,
  `knowledgeReason`) and in the `reconcile.knowledge.outcome` metric. It never changes the route to a resolution.
- **There was no retry of a rate-limited EKA lookup in the first run** (by design). If the lookup matters more than
  latency, run fail-closed and re-run the failed jobs, as D did. A short backoff retry on `RATE_LIMITED` honouring
  `Retry-After: 1` was added afterwards as a follow-up (section 9) and measured (section 10).
- **The default pool (2 to 4) keeps reconcile-ai below the EKA limit by itself**: in A and B there was no EKA or gateway
  429. The 429s on EKA and the gateway appear only when the pool is widened. Raising the pool needs the EKA edge limit
  (or a shared Redis limiter, ADR-025) raised as well.
- Single laptop, the stub model (about 0.4 to 0.9 s per call), the in-process embedding model on the EKA core. Real
  provider latency would dominate and was deliberately not measured (no paid call).
- Not covered: more than 25 parallel, more than one replica of any service, a second tenant loading the same EKA user,
  streaming, agent modes (they do not use knowledge).
- Test-harness note: the first fake-embeddings server did not handle chunked request bodies and keep-alive, which showed
  up as failed evidence ingestion and one `ResourceAccessException` job failure (`FAILED_RETRYABLE`, graceful). That was
  the harness, fixed in `fake_embeddings.py`; none of the table's runs include it.

## 6. Leak check

After all runs, `scripts/int4/leak.py` scanned the reconcile-ai logs of every run and the container logs of the gateway,
the EKA edge, core and worker, and Keycloak. Patterns: a JWT shape, a `Bearer` value, the analyst note text, the stub
reply text and proposal root cause, evidence text (counterparty, reference, amount) and the stub directive marker. Exact
values of the four client and admin secrets from the platform `.env` were counted too (compared in memory, never printed).

Result: **0 hits for every pattern in every log, 0 hits for every secret value.** Logs carry outcome codes, counts and
ids (`knowledge lookup tenant=... outcome=... reason=... hits=...`), as `EkaKnowledgeClient` documents. The 429 bodies
from EKA and the gateway carry codes and correlation ids only. Limits of the check: the EKA edge log read back as 1000
lines (the Docker tail, so older lines could be missing), and the gateway ledger rows and database contents were not
scanned (`PayloadLeakageIT` in ai-gateway covers the ledger). **Both gaps are closed in section 8.**

## 7. Reproduce

From the platform repo root, with the git-ignored `.env` filled in and Docker running (sibling repos at `GATEWAY_DIR`,
`EKA_DIR`, `RECONCILE_AI_DIR`):

```bash
docker compose --env-file .env -f compose/int4/keycloak.yaml up -d
( cd "$GATEWAY_DIR/deploy" && GATEWAY_CACHE_KEY=$(openssl rand -base64 32) docker compose -p platform-int4-gw \
  -f compose.yaml -f <platform>/compose/int4/gateway.override.yaml up -d --wait )
( cd "$EKA_DIR" && EKA_EDGE_OIDC_ISSUER=http://localhost:8590/realms/platform \
  EKA_EDGE_OIDC_JWK_SET_URI=http://host.docker.internal:8590/realms/platform/protocol/openid-connect/certs \
  docker compose -p platform-int4-eka -f docker-compose.yml -f <platform>/compose/int4/eka.override.yaml --profile edge up -d --build )
python scripts/int4/fake_embeddings.py 8599 &
bash scripts/int4/start_app.sh <jar> <log> 8585 KNOWLEDGE_FAIL_CLOSED=false \
  RECONCILE_INVESTIGATION_COREPOOLSIZE=25 RECONCILE_INVESTIGATION_MAXPOOLSIZE=25 \
  RECONCILE_RATELIMIT_CAPACITY=100 RECONCILE_RATELIMIT_REFILLPERMINUTE=100
python scripts/int4/load.py --base http://localhost:8585 --n 25 --token-file <analyst token file> \
  --evidence <sample evidence file> --tag C --out run-C.json     # add --rerun-failed for the fail-closed run
bash scripts/int4/down.sh
```

Seed one EKA procedure document per tenant first (as the tenant's service identity) for `OK` lookups. Mint the local
analyst token with `JwtTestSupport` (see `scripts/generate-local-jwt.ps1`). Run E by `docker pause`-ing the
`platform-int4-eka-core-behind-edge-1` container during the load and unpausing it afterwards.

---

# Follow-ups (INT-4b, 2026-11-19, same day)

Run after section 7 on a second throwaway stack with its own project names and ports (`platform-int4b*`; Keycloak 8790,
gateway 8780, EKA edge 8880, reconcile-ai Postgres 5733, EKA Postgres 5732, fake embeddings 8799, app 8785 and 8786).
Same machine, still free except section 13. Raw data: `int-4-raw/` (run-F*, run-G*, run-T*, run-R*, run-L*,
tenant-burst-*, metrics-*, leak-result-int4b.txt, log-line-counts-int4b.txt). CI run id: pending push.

## 8. Leak scan gaps closed

- **Full logs, no truncation.** `scripts/int4/collect_logs.sh` writes `docker logs --since <start>` of every container of the
  project to files and prints line counts (no tail limit). Counts for this stack: EKA edge 1653 lines, EKA core 493, EKA
  worker 7, EKA api 31, gateway 40 (stub period) and 40 (live period), Keycloak 19, the Postgres and Redis containers 27
  to 89 each (`int-4-raw/log-line-counts-int4b.txt`), plus 18 reconcile-ai logs of 67 to 285 lines each. The gateway logs
  only a handful of lines unless something fails, which is why its logs are short.
- **Database scan.** `scripts/int4/leak.py` now also searches every column of every table (the row cast to text, and the hex
  form of the marker for `bytea`) in the gateway database (ledger, audit, idempotency, cache, outbox), the reconcile-ai
  database (`llm_call`, `gateway_call`, `audit_event`, `investigation_job`, `resolution_proposal`, `approval_task`,
  `outbox_event`, evidence tables) and the EKA database. Markers planted in the test evidence: a unique canary string and a
  fake secret value shaped like an API key (an `api_key=sk-...` line). Also searched: 53 values in all (the whole token,
  payload part and signature part of every bearer token still on disk, the exact client and admin secrets and the OpenAI
  key from the environment) and any JWT-shaped value. Markers go to `psql` on stdin and are never printed. Any hit outside
  the evidence store fails the run (exit 1).
- **Result: PASS.** 0 hits in 18 reconcile-ai logs and 13 container logs (JWT shape, Bearer value, note text, stub text,
  evidence text, canary, fake secret, bearer tokens, secret values). Databases: gateway 22 tables, 0 hits (the ledger carries
  metadata only); EKA 6 tables, 0 hits (EKA never saw the evidence text); reconcile-ai 18 tables, 0 hits outside
  `evidence_content` and `vector_store`, where the canary and the fake secret are present as expected (the customer's own
  evidence in its own database; this is also the positive control that proves the scan can find them: 651 and 640 rows).
  **Negative control:** the scan was re-run with a marker known to be present (`tenant-a`): it failed and listed 12 tables
  and the log files.
- **The fake secret is stored raw in the evidence tables** and in the vector store (the product keeps evidence as uploaded).
  It did not reach the gateway ledger, `llm_call`, the audit chain, EKA, or any log. Whether it reached the model prompt was
  not inspected (the stub does not echo prompts). Treat "secrets in uploaded evidence" as a tenant-data-handling point, not
  as a log or ledger leak.
- **A gap I created:** recreating the gateway container for the paid step (section 13) discarded the stub-period gateway log
  of runs F1 to G100w (that container was replaced). A second stub gateway then served runs T5 and F3 (a repeat of the
  two-tenant run and of the 25-parallel run), and its full log is in the scan. The log of the first stub gateway was sampled
  (one error pattern, section 10) but not scanned in full. The gateway database persisted and was scanned after all runs.
- Driver note: the `audit_verify` field in `run-*.json` is `HTTP 403` (the load driver's analyst token lacks `SRE_ADMIN`).
  The chains were verified with an `SRE_ADMIN` token in section 12 (valid, 1160 to 1466 events).

## 9. Code: bounded retry of an EKA lookup on HTTP 429

`EkaKnowledgeClient` (reconcile-ai), properties under `reconcile.knowledge.*`:

| Property (env) | Default | Meaning |
|---|---|---|
| `rate-limit-retries` (`KNOWLEDGE_RATE_LIMIT_RETRIES`) | 2 | retries per lookup after a 429; 0 turns retrying off |
| `rate-limit-max-wait` (`KNOWLEDGE_RATE_LIMIT_MAX_WAIT`) | 2s | cap for one wait, whatever `Retry-After` says |
| `rate-limit-jitter` (`KNOWLEDGE_RATE_LIMIT_JITTER`) | 250ms | random 0 to this added to `Retry-After` |

Rules: wait `Retry-After` seconds (500 ms if the header is missing or not a number) plus jitter, then cap at
`rate-limit-max-wait`. A wait that does not fit in what is left of the per-lookup `request-timeout` (5 s) is skipped and the
lookup ends `RATE_LIMITED`, so a lookup never exceeds the existing timeout. **Only 429 is retried**: other 4xx, 5xx and
timeouts are not (the circuit breaker handles those), and the breaker sees one result per lookup, not one per attempt.
`RATE_LIMITED` is recorded in the outcome metric and on the job only after the retries are used up. Fail-open and
fail-closed behave as before on the final result. New metric `reconcile.knowledge.rate_limit_retry{event=retry|exhausted}`.
The log line per retry carries the attempt number and the wait, never the query, a hit or a token. No new dependency, no
WebFlux, auto-resolve untouched.

Tests: `EkaKnowledgeClientRetryTest` (12 unit tests, no real waiting): 429 then 200 (one retry, waited `Retry-After`, outcome
OK, one outcome recorded); 429 forever (1 + 2 calls, `RATE_LIMITED` once, events retry, retry, exhausted); cap (30 s asked,
2 s used; 1.9 s plus jitter still capped) and jitter bounds (1000 to 1250 ms for `Retry-After: 1`); missing or HTTP-date
`Retry-After` (500 ms plus jitter); jitter differs between lookups; a wait that does not fit the timeout is skipped; retries
0 turns it off; no retry on 404, 500 or a timeout; the breaker counts one failure per lookup. Integration tests against the
existing stub EKA (`FakeEkaServer`, real Postgres, no database mocks): `KnowledgeOutcomeIT` (429 then OK: job sees `OK`, 2 EKA
calls; 3 x 429: fail-open job `SUCCEEDED` with `UNAVAILABLE / RATE_LIMITED`, 3 EKA calls) and `KnowledgeFailClosedIT` (3 x
429: job `FAILED / KNOWLEDGE_UNAVAILABLE`, no `llm_call`). Results: unit `EkaKnowledgeClientRetryTest` 12,
`EkaKnowledgeClientTest` 14, `KnowledgeArchitectureTest` 3, `KnowledgeResultTest` 4, all pass; IT `Knowledge*IT`,
`BreakSpecificKnowledgeQueryIT`, `GenericKnowledgeQuerySwitchIT`: 17 tests, 0 failures. The old `rateLimitIsUnavailable` test
(one 429 means `RATE_LIMITED`) was replaced by the 429-forever test, since one 429 is now retried. Built from a temp copy of
the repository; the repository `target/` was not touched.

## 10. Measurements (stub model; compare with section 3 and E-63)

Same driver and scenarios as section 3, with the retry in place. "Wide" = pool equal to N, start limiter raised above N.
Default = pool 2 to 4, start limiter 20 burst. Latency from the start POST to the terminal job status.

| Run | Config | N | Start HTTP | Jobs | Knowledge | e2e p50 / p95 (ms) | Wall (ms) | EKA retry / exhausted |
|---|---|---|---|---|---|---|---|---|
| F1 | wide, fail-open, tenant-a, **host under CPU contention** (see below) | 25 | 202 x 25 | 18 SUCCEEDED, **7 FAILED** (4 `GATEWAY_INTERNAL_ERROR`, 3 `GATEWAY_TENANT_CONCURRENCY_LIMIT`) | OK x 21, UNAVAILABLE x 4 (TIMEOUT) | 15603 / 16341 | 16358 | 0 / 0 |
| F1b | same as F1, host quiet | 25 | 202 x 25 | 25 SUCCEEDED | OK x 25 | 6815 / 7316 | 8062 | 4 / 0 |
| F3 | same again, fresh stub gateway (the leak-scan run) | 25 | 202 x 25 | 25 SUCCEEDED | OK x 25 | 5807 / 6084 | n/a | 3 / 0 |
| F2 | wide, **fail-closed**, tenant-b | 25 | 202 x 25 | 25 SUCCEEDED, 0 FAILED | OK x 25 | 7513 / 8254 | 8638 | 4 / 0 |
| G50d | default, tenant-a | 50 | 202 x 20, **429 x 30** | 20 SUCCEEDED | OK x 20 | 5188 / 6678 | 6710 | 0 / 0 |
| G50w | wide (pool 50), fail-open, tenant-b | 50 | 202 x 50 | 32 SUCCEEDED, **18 FAILED** (`GATEWAY_TENANT_CONCURRENCY_LIMIT`) | OK x 20, UNAVAILABLE x 30 (TIMEOUT 24, HTTP_500 5, RATE_LIMITED 1) | 14100 / 14651 | 14717 | 11 / 1 |
| G100d | default, tenant-a | 100 | 202 x 20, **429 x 80** | 20 SUCCEEDED | OK x 20 | 5684 / 7750 | 7794 | 0 / 0 |
| G100w | wide (pool 100), fail-open, tenant-b | 100 | 202 x 100 | 94 SUCCEEDED, **6 FAILED** (`GATEWAY_TENANT_CONCURRENCY_LIMIT`) | OK x 20, UNAVAILABLE x 80 (RATE_LIMITED 42, TIMEOUT 16, HTTP_503 11, HTTP_500 10, CIRCUIT_OPEN 1) | 17181 / 17948 | 18219 | 112 / 42 |

Success rate (job `SUCCEEDED` with a proposal on the manual-review route, of accepted starts): F1b 100 percent, F3 100, F2
100, F1 72 (contended), G50d 100 (of 20 accepted; 30 of 50 refused at the door), G50w 64, G100d 100 (of 20 accepted; 80 of
100 refused), G100w 94. Start POST latency p50 / p95: 340 / 566 ms (F1b) up to 1390 / 2645 ms with a 5.5 s max (G100w).
Every failed job is `FAILED_RETRYABLE` on its break (not stuck), none is `RESOLVED`: of the 381 jobs that reached a terminal
state in runs F to T, 331 routed `MANUAL_REVIEW`, 11 `L1_APPROVAL` (the paid run) and 39 failed retryably; the other 110
starts were refused at the door by the start limiter and their breaks stayed `READY_TO_INVESTIGATE`.

Comparison with E-63 (same N = 25 wide):

- **Fail-open (E-63 C versus F1b, F3).** E-63 C: 25/25 jobs, knowledge OK x 23, 2 `RATE_LIMITED`. With the retry: OK x 25 in
  both quiet runs (3 to 4 EKA 429s each, all recovered after about 1 to 1.25 s), latency unchanged within noise (p50 6.5 s
  versus 6.8 s and 5.8 s). The retry removes the knowledge gap at N = 25 at no visible latency cost.
- **Fail-closed (E-63 D versus F2).** E-63 D: 3 of 25 `FAILED / KNOWLEDGE_UNAVAILABLE` on the first pass (2 `RATE_LIMITED`, 1
  `TIMEOUT`), 25/25 only after a re-run. With the retry: 25/25 on the first pass, no re-run. (One run each: the one timeout in
  D did not recur here, so part of the improvement may be timing luck.)
- **F1 is not comparable.** During F1 and the start of F2 another process on the machine (a kind cluster) used 290 to 460
  percent CPU. The gateway's database pool (10 connections) ran out (`Connection is not available, request timed out after
  3384ms`, in the gateway log), giving `GATEWAY_INTERNAL_ERROR`, and 4 EKA lookups timed out at 5 s. The same scenario with the
  CPU free (F1b, F3) was clean. Read F1 as "a CPU-starved host makes a 25-parallel burst fail visibly but safely", not as a
  regression of the retry (F1 recorded 0 retry events).
- **N = 50 and 100, default pool.** The start limiter (burst 20, per tenant, per instance) answers 429 with `Retry-After` to
  the rest at the door: 20 accepted, all succeed, p50 about 5.2 to 5.7 s (queue wait dominates: queue p95 5.9 and 6.4 s). Safe
  and predictable, but only 20 of 50 or 100 are served unless the caller retries after the header.
- **N = 50 and 100, wide pool.** All starts are accepted; the limits then sit downstream. EKA admits 20 at once and refills 5
  per second, so of 100 simultaneous lookups only 20 are `OK`; the retry (112 retries, 42 exhausted) cannot cover a deficit
  that needs about 16 s to refill inside a 5 s lookup budget. The rest end `UNAVAILABLE` (rate limit, edge 503, 500,
  timeouts, one `CIRCUIT_OPEN`); fail-open still completes them without procedure context (80 percent of G100w).
  ai-gateway: the per-tenant concurrency cap (`gateway.resilience.tenant-max-concurrent`, default 8) refuses the surplus with
  429; reconcile-ai's one same-key retry saves most, but 18 of 50 (G50w) and 6 of 100 (G100w) still ended `FAILED /
  GATEWAY_TENANT_CONCURRENCY_LIMIT` (retryable, nothing sent to the provider). Which jobs lose depends on timing, so 18-of-50
  versus 6-of-100 is noise, not a trend.
- **What this says.** The default pool (2 to 4) is the right size for these downstream limits. If the pool is widened, raise
  the EKA edge limit and the gateway tenant cap with it. The EKA retry helps when the limit is exceeded only slightly (N about
  25); it is not a capacity mechanism.

## 11. Two tenants in parallel (one reconcile-ai instance, wide pool 40)

Setup: one EKA procedure document per tenant (one each, confirmed in EKA), each tenant mapped to its own service client
(`svc-reconcile-tenant-a` and `-b`, as in section 2). tenant-a and tenant-b each ran 20 investigations at the same moment
(run T4a with `KNOWLEDGE_RATE_LIMIT_RETRIES=0`, so a shared limiter could not be hidden by retries; run T5 with the defaults).
Each tenant's evidence carried its own marker (`TENANT-A-EV`, `TENANT-B-EV`).

| Check | Result |
|---|---|
| Cross-tenant knowledge | The 188 jobs with `knowledge_refs` over runs F1 to T4a: tenant-a jobs cite exactly one chunk, tenant-b jobs exactly one, in different EKA owners (checked in the EKA database; owner overlap 0). |
| Cross-tenant evidence | `evidence_content`: the tenant-a marker is in 20 rows, all tenant-a; the tenant-b marker in 20 rows, all tenant-b. 0 jobs whose tenant differs from their break's tenant. |
| API | A tenant-b token reading a tenant-a job and a tenant-a proposal: 404 and 404. |
| EKA rate bucket per tenant? | **Per caller identity, so per tenant client.** T4a: 40 lookups at once (20 + 20, each at its own burst limit): 0 `RATE_LIMITED`; a shared bucket would have refused about 20. Direct burst: tenant-a 60 parallel searches got 22 x 200 and 38 x 429 while tenant-b, 5 at the same moment, got 5 x 200. |
| Gateway limits per tenant? | Yes. tenant-a flooding 60 chat calls got 41 x 200 and 19 x 429 `RATE_LIMITED` while tenant-b's 5 calls all got 200. The concurrency cap is per tenant too (tenant-b's own 15 at once got 6 `TENANT_CONCURRENCY_LIMIT`, its own cap, unrelated to A). |
| Latency of tenant-b while tenant-a floods | EKA search p50 2830 ms versus 2242 ms alone (5 concurrent); gateway chat p50 2257 versus 2069 ms. About 0.2 to 0.6 s slower, no refusals. |

**Noisy-neighbour finding (a shared resource, not the rate bucket).** The EKA edge passes calls to the core through one circuit
breaker and one concurrency bulkhead shared by all users (edge log: `BulkheadFullException: Bulkhead 'core' is full`). In the
first burst, tenant-a's 60 simultaneous searches made tenant-b's 15 concurrent searches get 7 x HTTP 503 with no 429, and
in T4a and T5 a few lookups of either tenant (3 and 2) got HTTP 500. So one tenant's flood can degrade another's lookup
through shared edge-to-core capacity even though the per-user rate limits are isolated.
Config remedy (not applied; EKA unchanged): raise the edge's core concurrency limit (it is Spring Cloud's Resilience4j default,
not set in EKA's `application.yml`; `resilience4j.bulkhead.instances.core.max-concurrent-calls` on the edge is the likely lever,
**not verified here**), keep the reconcile-ai pool small so each tenant has only a few lookups in flight, and run the edge
limiter on Redis when there is more than one edge replica (`EKA_EDGE_RATE_LIMIT_REDIS_URL`). reconcile-ai side: the retry
does not cover 503 and 500 by design (the breaker does). No code change was made: the only per-tenant-shared thing in
reconcile-ai is the one executor pool, and the remedy is the same.

## 12. Two reconcile-ai replicas on one Postgres (direct calls to two ports, no proxy)

Replica A on 8785, replica B on 8786, same database, EKA and gateway; lease 20 s and sweep 5 s for the crash test.

| Check | Result |
|---|---|
| Jobs not run twice | Limiter test: 40 jobs started (20 on each replica), 40 `llm_call` rows, 0 jobs with more than one; every job has one `gateway_call` attempt (`SUCCESS`, 200); `owner_node` split 20 / 20. The claim is one atomic `UPDATE ... WHERE status = QUEUED` (`InvestigationJobRepository.claim`), not `SKIP LOCKED` (that is used by the outbox relay). |
| Same break started on both replicas at the same moment (10 breaks, 20 requests) | Exactly one job per break: replica A 10 x 202, replica B 10 x 409 `INVESTIGATION_IN_PROGRESS`. 0 breaks with more than one job (checked over the whole database). All 10 jobs `SUCCEEDED`, 10 `llm_call`. A won all ten because its threads started first; the race was not made fair, so this shows the check works, not that every interleaving is safe. |
| Start limiter across instances | **Per instance with the default `RATE_LIMIT_STORE=memory`**: tenant-a sent 30 starts to each replica: 20 x 202 and 10 x 429 on each = 40 accepted against a configured 20. With `RATE_LIMIT_STORE=redis` (a throwaway Redis, `SPRING_DATA_REDIS_HOST` and `_PORT`): 2 + 18 = 20 accepted, 40 x 429, one shared bucket. Remedy: use the Redis store when running more than one replica. |
| Audit chain | Valid on both replicas after each phase (`/audit/verify`, `SRE_ADMIN`): 1160, 1230 and 1466 events, `valid: true`, same head hash from both replicas. |
| Replica A killed while holding work (18 starts, kill after 0.25 s) | 8 jobs were already claimed by A: 6 finished before the kill; 2 were `RUNNING` and ended `FAILED / INTERRUPTED` (break `FAILED_RETRYABLE`, not flagged outcome-unknown) when B's sweep found the stale heartbeat. The other 10 were still `QUEUED`: B resubmitted and ran them (owner node switched to B), all `SUCCEEDED`. 16 `llm_call` rows for 16 successful jobs, none duplicated. Settled in about 24 s (lease 20 s plus sweep 5 s). |

Not tested: more than two replicas, a real load balancer in front, a replica restarting during an in-flight model call (the
code marks that case outcome-unknown and does not redo it).

## 13. Real provider latency (one small paid run, gateway on the `real-provider` profile)

The gateway was recreated with `gateway.live.override.yaml` (profile `real-provider`, `gateway.routing.models=openai-gpt-4o-mini`;
the key was passed from the launching shell's environment to the gateway container only, never printed or written; the same
pattern as the E-51 live run). reconcile-ai embeddings still went to the local stand-in (no embeddings spend). A plain analyst
note (no stub directive) and evidence without the fake secret were used. 1 pilot investigation, then 10 at once (default pool,
4 workers), tenant-a: 11 calls in all (cap was 15).

| Measure | Value |
|---|---|
| Jobs | 11 `SUCCEEDED`, 0 failed; route `L1_APPROVAL` x 11 (the real answers cite evidence, so they go to approval); breaks `PENDING_L1`; none resolved |
| Model call latency (reconcile-ai `llm_call`) | p50 2959 ms, p95 4840 ms, min 2299, max 5043 |
| Job run time | p50 3574 ms, p95 5176 ms |
| End to end, 10 parallel starts (queued behind 4 workers) | p50 11587 ms, p95 17537 ms (queue wait p95 14.4 s) |
| Tokens | 9010 prompt, 1257 completion (about 820 and 114 per call) |
| **Spend** | **USD 0.002106** (9010 x 0.15 and 1257 x 0.60 per million; the gateway ledger `actual_cost_usd` sums to 0.00210570 over the same 11 rows, provider OPENAI, status OK) |

The hard cap was USD 0.40; about 0.5 percent of it was used. Gateway `provider_latency_ms` averaged 3127 ms. The stub answered
in 0.4 to 0.9 s, so the real model adds about 2 to 4 s per investigation and becomes the dominant latency; with 4 workers
and about 3.6 s per job, the last of 10 parallel starts waits about 14 s in the queue. The key appears in no log, ledger row,
table or result file (section 8 includes its value among the 53 searched values; the scan ran after this step).

## 14. Limits of this evidence

- One laptop, shared with other work; F1 shows that CPU contention changes the picture. Every figure is for this machine on
  this day, not a capacity claim. One run per scenario, except the 25-parallel fail-open (three runs).
- The stub model, except section 13: 11 real calls of about 820 prompt tokens each is a tiny sample (the p95 of 11 values is
  close to the maximum). Larger evidence, other models and a real provider's own 429s were not measured.
- At most 100 parallel starts, two replicas, two tenants (each at most 20 in parallel through reconcile-ai, plus direct
  bursts of 60 + 15 and 60 + 5).
- The gateway log of runs F1 to G100w was not scanned in full (section 8). Not run: fail-closed at N = 50 or 100, a retry
  count above 2, the EKA bulkhead remedy (unverified).
- Fake embeddings: investigations used deterministic stand-in vectors, so retrieval quality is not tested here.
- Not run in CI (needs Docker and three sibling repositories). CI run id: pending push.

Reproduce additions (platform repo, `scripts/int4/`): `env-b.sh` (ports and project name), `up.sh`, `seed_eka.sh`,
`collect_logs.sh`, `leak.py` (extended), `tenant_burst.py`, `replicas.py`, `replicas_crash.py`, `down.sh`; `compose/int4/*.yaml`
take their ports from `INT4_*` variables; `compose/int4/gateway.live.override.yaml` is for section 13 only. Tokens for the
analyst and `SRE_ADMIN` personas were minted with `JwtTestSupport` from a temp build copy.
