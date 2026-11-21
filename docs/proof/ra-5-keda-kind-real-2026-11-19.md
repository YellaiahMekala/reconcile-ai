# RA-5: KEDA overlay with the real image, real metrics and a real Prometheus (2026-11-19)

Follow-up of PQ1.2.1 (`pq1-2-1-keda-kind-2026-11-19.md`), which used a stub app and a stub Prometheus. This run
replaces both. Local kind cluster, no AWS, no paid API (0.00 USD). CI run: pending push.

## What was real, what was not

Real:

- The app image built with `docker build` from the repo `Dockerfile` (multi-stage, Temurin 21 JRE, non-root,
  810 MB). Spring Boot 3.5.16, Spring MVC, no WebFlux. 2 replicas Ready with 0 restarts, startup about 46 s.
- Real PostgreSQL 16 with pgvector (Flyway migrations, Row-Level Security, `reconcile_app` role) and Redis from
  `overlays/kind/dev-deps.yaml`. The database is never mocked.
- Real Prometheus v2.55.1 with pod service discovery, scrape interval 5 s, scraping `/actuator/prometheus` through the
  Deployment's own `prometheus.io/*` annotations. The scrape adds the `namespace` and `pod` labels
  (`up{namespace="reconcile"}` returned both pods; `reconcile_investigations_executor_active/queued{namespace="reconcile"}`
  returned one series per pod). This is the label the ScaledObject query filters on.
- Real executor metrics `reconcile_investigations_executor_active` and `_queued` from `InvestigationQueueMetrics`.
- KEDA 2.21.1 (Helm chart 2.21.1), metrics-server v0.7.2, kind v0.25.0, Kubernetes v1.31.2, one node, 6 CPUs and
  9.7 GB shared with other containers on the machine (so pod start-up was slower than on an idle node).
- The unmodified `overlays/keda/scaledobject.yaml`: Prometheus trigger (active + queued, threshold 3 per pod,
  `AverageValue`) plus CPU trigger (Utilization 70%), min 2, max 6, scale-up 2 pods per 30 s, scale-down window 300 s,
  1 pod per 60 s. The pods keep the base requests: 250m CPU, 512Mi.
- Real investigations: break, evidence upload, ingestion into pgvector, `POST /breaks/{id}/investigations`, bounded
  executor (pool 4, queue 50), advisor chain, citation verification, policy gate, persisted jobs.

Substituted (disclosed):

- Embeddings: the app always calls OpenAI `text-embedding-3-small`, which is paid. `SPRING_AI_OPENAI_BASE_URL` points at
  a small in-cluster stub of `POST /v1/embeddings` returning deterministic 1536-dim vectors (`embedding-stub.yaml`).
  The pgvector writes and HNSW index are real. Retrieval quality is not what this run measures.
- Chat: the app's own `mock-llm` profile (zero tokens). Evidence containing `simulate slow provider` makes each model
  call sleep 3 s, which stands in for a slow real provider and makes jobs occupy executor threads.
- Login: `demo-login` profile mints a dev token (throwaway dev key mounted from a Secret created by hand; the key
  is the repo's dev key). `RATE_LIMIT_ENABLED=false` so one load generator can exceed the per-tenant 20 per minute.
  Both are set only in `overlays/keda-kind-real/app-patch.yaml`. Everything else is the base and `../keda`.

Harness: `k8s/overlays/keda-kind-real/` (kustomization, app patch, embedding stub, Prometheus, load generator). The
load generator (`loadgen.yaml`, a Job running a stdlib Python script) is applied by hand, not part of the kustomization.

## Setup

```
docker build -t reconcile-ai:keda-proof .
kind create cluster --name kind-keda --image kindest/node:v1.31.2
docker save reconcile-ai:keda-proof | docker exec --privileged -i kind-keda-control-plane ctr -n k8s.io images import -
#  (kind load docker-image failed here: "ctr: content digest ... not found", an all-platforms import quirk with Docker Desktop;
#   the save-and-import above works)
helm install keda kedacore/keda --version 2.21.1 -n keda --create-namespace --wait
kubectl apply -f <metrics-server v0.7.2 components.yaml> + --kubelet-insecure-tls
kubectl create namespace reconcile
kubectl -n reconcile create secret generic demo-login-key --from-file=dev-private-pkcs8.pem=scripts/jwt/dev-private-pkcs8.pem
kubectl apply -f k8s/overlays/kind/dev-deps.yaml
kubectl apply -k k8s/overlays/keda-kind-real
kubectl apply -f k8s/overlays/keda-kind-real/loadgen.yaml   # (edited per phase: MODE, THREADS, DURATION)
```

ScaledObject was `READY True` with triggers `prometheus,cpu`; only `keda-hpa-reconcile-ai` exists (base HPA removed).
Observation sampler: `docs/proof/ra-5-raw/watch.sh` every 10 s (ready/desired replicas from the Deployment, HPA targets,
the Prometheus value `sum(active) + sum(queued)` queried directly, `kubectl top`). Raw timelines are in `ra-5-raw/`.
In the tables below "ready/desired" is `readyReplicas/spec.replicas`.

## Findings from getting it to run

1. Stub embedding server must read chunked request bodies: Spring AI's client sends the embeddings request without
   `Content-Length`, my first stub read 0 bytes and returned no vectors, so ingestion failed with
   `ArrayIndexOutOfBoundsException` in `EmbeddingModel.embed`. Stub fixed; not an app bug.
2. The app reaches 1 full CPU during JVM start (`kubectl top` 986m-1002m per pod against a 250m request) for roughly
   one minute. With the CPU trigger at 70%, a fresh install (or a rollout) therefore scales out by itself: on first
   start 2 became 4, then 5, then 6 replicas with no load, and each new pod added more start-up CPU. Observed, then
   scaled back in once pods were idle (see "scale-in" below). Mitigation to consider: raise `requests.cpu`, add a
   startup grace via HPA `behavior`, or accept it. Not changed in this task.
3. Calls through the Service balance per TCP connection, so new pods only get load as new requests arrive; fine for
   this load generator (new connection per request).

## Phase B: backlog-driven scale-out (investigations)

Start state: 2/2 Ready, idle (CPU 2-6%, Prometheus sum 0). Load: 4 threads, each looping create break, upload
evidence (slow-provider text), wait for READY, `POST /investigations`, 240 s, started 16:53:0x.
Result of the generator: 788 accepted (`202`), 248 rejected `503` (capacity). Database afterwards: all 788 accepted jobs
(plus 192 from an earlier smoke run) `SUCCEEDED`, the 248 `FAILED` with the capacity reason: nothing was lost or stuck.

| Time | Ready/desired | Prometheus active+queued | HPA targets |
|---|---|---|---|
| 16:52:55 | 2/2 | 0 | prom `0/3`, cpu 6% |
| 16:53:07 | 2/2 | 19 | prom `0/3` (not yet polled) |
| 16:53:18 | 2/4 | 61 | prom `19500m/3`, cpu **2%** |
| 16:53:54 | 2/6 | 101 | prom `25750m/3`, cpu 322% |
| 16:55:18 | 3/6 | 104 | prom `17667m/3`, cpu 214% |
| 16:55:43 | 5/6 | 115 | prom `18167m/3` |
| 16:56:08 | 5/6 | 159 | prom `24/3` |
| 16:57:10 | 5/6 | 210 (peak seen: 253) | prom `43/3` |
| 16:58:08 | 6/6 | 11 | prom `2334m/3` |

- Desired replicas went 2 to 4 within about 11 s of the first back-log sample (about 16:53:07 to 16:53:18), at a moment
  when CPU still read 2%: the Prometheus trigger acted before the CPU trigger could. 6 desired by 16:53:54.
- Ready replicas lagged desired by about 5 minutes (2 to 3 ready at 16:55:18, 6 ready at 16:58:08): each new pod
  needs 45 s or more to start and the node was CPU-saturated (all pods 800-1000m). With only 2 ready pods the
  executors held 105 back-logged jobs (2 x (4 active + 50 queued) = 108 slots) and the app answered 503, as designed.
  The 248 rejected requests are the cost of that lag; KEDA did not prevent them, scale-out speed here is bounded by
  start-up time (startupProbe allows 3 min).
- Per-pod peak queue before the run ended: 50 on four pods (the full bounded queue), seen with
  `max_over_time(reconcile_investigations_executor_queued[1h])`.
- Pod restarts: 0 for all replicas for the whole run (no OOM at 6 replicas).

## Phase C: CPU trigger alone

Start state: back to 2/2 idle. Load: `GET /api/v1/breaks?size=50` from 8 threads for 240 s, no model work, so the
Prometheus sum stays 0 and only the CPU trigger can scale. 23,699 requests, all `200`.

| Time | Ready/desired | Prometheus sum | HPA targets |
|---|---|---|---|
| 17:07:29 | 2/2 | 0 | cpu 2% |
| 17:07:41 | 2/2 | 0 | cpu 4% (pods at 575m, 621m) |
| 17:07:52 | 2/4 | 0 | prom `0/3`, cpu **234%/70%** |
| 17:08:27 | 2/6 | 0 | cpu 220% |
| 17:09:37 | 4/6 | 0 | cpu 114% |
| 17:10:13 | 6/6 | 0 | cpu 170% |

Peak CPU reading 234% of the 250m request. The Prometheus trigger read `0/3` throughout, so the scale-out from 2 to 6
was caused by the CPU trigger alone. (Caveat: the node is shared and the load generator runs on it, so CPU is
inflated by contention and by new-pod start-up.)

## Scale-in (observed twice)

- After Phase B: the back-log drained at about 16:58:20 (sum 0). First reduction 6 to 5 at 17:03:21, then 5/5 to 4/4
  at 17:04:21, 3/3 at 17:05:26, 2/2 at 17:06:22. That is the 300 s stabilization window (about 5 min after the metric
  reached 0) and then one pod per minute, down to the minimum 2, which it held.
- After Phase C: load ended about 17:11:15. 6 to 5 at 17:16:38, 4 at 17:17:47, 3 at 17:18:41, 2 at 17:19:46: the
  same pattern. KEDA/HPA never went below `minReplicaCount: 2`.
- The unplanned start-up scale-out (finding 2) scaled in the same way: 6 to 2 over about 12 minutes once idle.

Final state: `ScaledObject READY True`, 2/2 pods, no restarts. Pod counts reached 6 each time (the maximum).

## Not proven

- Real OpenAI embeddings and a real model call: stubbed or mock (cost 0.00 USD). Latency per investigation is the
  mock's 3 s sleep plus real DB work, not a provider's.
- Prometheus-outage behaviour with the real stack (PQ1.2.1 covered it with the stub): not repeated.
- One node and one run per phase. Numbers vary with node contention and are not benchmark figures; they show that
  scale-out and scale-in happen, in which order, and with what lags.
- The trade-off in finding 2 (start-up CPU triggers CPU-based scale-out) is documented, not fixed.
- Rate limiting was off; the Redis-shared limit across replicas was not exercised here.

## Cleanup

`kind delete cluster --name kind-keda`; the local image `reconcile-ai:keda-proof` was removed. No cloud resources.

## Files

- `k8s/overlays/keda-kind-real/` (new: kustomization, app-patch, embedding-stub, prometheus, loadgen, dev-secret and
  wait-for-postgres copies from `overlays/kind`)
- `docs/proof/ra-5-raw/` (timelines and the sampler and load scripts)
