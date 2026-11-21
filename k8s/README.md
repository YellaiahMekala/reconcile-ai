# Kubernetes deployment

Plain manifests plus Kustomize (built into `kubectl`): a production-shaped `base/` and a local `overlays/kind/`
that adds in-cluster Postgres (pgvector) and Redis with throwaway credentials.

```
k8s/
  base/            namespace, service account, ConfigMap, Deployment, Service, HPA, PodDisruptionBudget
  overlays/kind/   dev Postgres + Redis, dev Secret, wait-for-postgres init container, local image tag
  overlays/keda/   KEDA ScaledObject on the investigation back-log; replaces the base HPA, shared Redis rate limit
  overlays/keda-kind-proof/  test harness only (PQ1.2.1): stub app and stub Prometheus
  overlays/keda-kind-real/   test harness only (RA-5): real image, real Prometheus, embedding stub, load generator
```

## What the base sets up

| Concern | How |
|---|---|
| Autoscaling | `HorizontalPodAutoscaler` (autoscaling/v2) on CPU: 2 to 6 replicas, target 70% of the 250m request. Scale-up is fast (up to 2 pods per 30 s), scale-down waits 5 minutes and removes 1 pod per minute so open SSE streams are not churned. |
| Several replicas being safe | `SSE_FANOUT=redis` (any pod can serve any progress stream), the daily digest and audit anchoring use Postgres advisory locks (one pod runs them), outbox relay uses `FOR UPDATE SKIP LOCKED`, the investigation lease lets any pod recover an orphaned job. |
| Health | `startupProbe` on `/actuator/health/liveness` (up to 3 min for Flyway and context start), `livenessProbe` on liveness, `readinessProbe` on readiness. |
| Rollouts | `maxUnavailable: 0`, `maxSurge: 1`, `PodDisruptionBudget minAvailable: 1`, graceful shutdown with a 45 s grace period. |
| Pod security | Non-root UID 10001, read-only root filesystem (writable `/tmp` emptyDir), all capabilities dropped, no privilege escalation, `RuntimeDefault` seccomp, no service-account token mounted. |
| Config and secrets | Non-secret settings in the `reconcile-ai-config` ConfigMap. DB credentials come from the `reconcile-ai-secrets` Secret and `OPENAI_API_KEY` from the optional `reconcile-ai-openai` Secret. Neither Secret is in `base/`; supply them from a secret manager (External Secrets, Sealed Secrets, CSI driver). |
| Metrics | Prometheus scrape annotations on `/actuator/prometheus`. |
| Spread | Topology spread across nodes (soft). |

## Try it on kind (validated on kind v0.25.0, Kubernetes v1.31.4)

```powershell
docker build -t reconcile-ai:local .
kind create cluster --name reconcile
kind load docker-image reconcile-ai:local --name reconcile

# metrics-server, needed by the HPA (kind's kubelet uses self-signed certs)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

kubectl create namespace reconcile
kubectl -n reconcile create secret generic reconcile-ai-openai --from-literal=OPENAI_API_KEY=$env:OPENAI_API_KEY
kubectl apply -k k8s/overlays/kind
kubectl -n reconcile rollout status deploy/reconcile-ai --timeout=5m
kubectl -n reconcile port-forward svc/reconcile-ai 18080:80
curl.exe -s http://localhost:18080/actuator/health/readiness
```

The app needs `OPENAI_API_KEY` at startup (embeddings). Any non-empty value lets it start; evidence ingestion needs a
real key.

Validated result on kind: both replicas Ready with 0 restarts, readiness/liveness UP, a break created and listed
through the Service with a dev token (RLS role in force), and under load from 4 in-cluster clients the HPA went from
2 to 4 replicas (`cpu: 381%/70%`). Watch it yourself:

```powershell
kubectl -n reconcile get hpa reconcile-ai -w
```

## KEDA overlay (scale on the investigation back-log)

`overlays/keda/` replaces the CPU-only HPA with a KEDA `ScaledObject`. Investigations are I/O-bound model calls, so CPU
stays low while the queue fills and the base HPA reacts late.

| Item | Value |
|---|---|
| What scales | the `reconcile-ai` Deployment |
| Trigger 1 (Prometheus) | `sum(reconcile_investigations_executor_active) + sum(reconcile_investigations_executor_queued)` in namespace `reconcile`, `AverageValue` target 3 per pod (a pod runs at most 4 and queues the rest) |
| Trigger 2 (CPU) | `Utilization` 70% |
| Replicas | min 2, max 6. `pollingInterval: 15` / `cooldownPeriod: 300` are kept, but KEDA 2.21 warns they only matter with `minReplicaCount: 0`; the real damping is the HPA `behavior` below |
| Behaviour | scale up 2 pods per 30 s; scale down 1 pod per 60 s after a 300 s window |
| Prometheus down | `ignoreNullValues: "false"`, so KEDA reports an error and holds the current replica count |
| Other changes | deletes the base HPA (KEDA creates its own) and sets `RATE_LIMIT_STORE=redis` so replicas share one rate-limit bucket per tenant |

Needs KEDA installed (`keda.sh/v1alpha1`) and a Prometheus that scrapes the pods. Set `serverAddress` in
`overlays/keda/scaledobject.yaml` to your Prometheus service (the default is `http://prometheus-server.monitoring.svc:80`).
Apply it instead of the base or kind overlay. It builds on `base/` only, so supply Postgres, Redis and the Secrets yourself:

```powershell
kubectl apply -k k8s/overlays/keda
```

CI renders this overlay and checks it with kubeconform (schema only). Scaling was also run on a kind cluster with KEDA 2.21.1 (PQ1.2.1, 2026-11-19): the ScaledObject became Ready and scaled the Deployment 2 to 4 to 6 as a stubbed Prometheus value rose, held its count while Prometheus was down, and scaled down one pod at a time after the 300 s window. That run used a busybox stand-in for the app and a static stub Prometheus (`overlays/keda-kind-proof/`, test harness only), not the real image or real metrics; it surfaced and fixed a missing `namespace` on the ScaledObject. Proof: `docs/proof/pq1-2-1-keda-kind-2026-11-19.md`.

The follow-up run (RA-5, 2026-11-19, `docs/proof/ra-5-keda-kind-real-2026-11-19.md`) used the real image, a real Prometheus with pod discovery (it adds the `namespace` label the query filters on) and real executor metrics, via `overlays/keda-kind-real/`. Under load of real investigations the back-log trigger took the Deployment from 2 to 6 replicas (desired 4 within about 11 s, while CPU still read 2%); CPU-only load did the same with the back-log at 0; scale-in went 6 to 2 one pod per minute after the 300 s window. Limits: embeddings stubbed (paid API), chat is the mock model, one node; pods need 45 s or more to become Ready, so with 2 pods the full queues refused 248 requests with 503 before the new pods were ready. JVM start-up uses about 1 CPU per pod for about a minute against a 250m request, so with the CPU trigger a fresh install or rollout can scale out by itself; consider a higher CPU request if that matters.

## Production notes (not in this repo)

- Use managed Postgres (with pgvector) and Redis; remove the dev overlay. Run Flyway as the owner role, the app as
  `reconcile_app` (RLS applies to it).
- Ingress with TLS, and a NetworkPolicy allowing only ingress-controller traffic to the pods.
- On EKS, IRSA gives the pods S3 access for audit anchoring without static keys.
- The Angular UI is not in this image; serve its static build from a CDN or an nginx Deployment behind the same Ingress.
