# Kubernetes deployment

Plain manifests plus Kustomize (built into `kubectl`): a production-shaped `base/` and a local `overlays/kind/`
that adds in-cluster Postgres (pgvector) and Redis with throwaway credentials.

```
k8s/
  base/            namespace, service account, ConfigMap, Deployment, Service, HPA, PodDisruptionBudget
  overlays/kind/   dev Postgres + Redis, dev Secret, wait-for-postgres init container, local image tag
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

## Production notes (not in this repo)

- Use managed Postgres (with pgvector) and Redis; remove the dev overlay. Run Flyway as the owner role, the app as
  `reconcile_app` (RLS applies to it).
- Ingress with TLS, and a NetworkPolicy allowing only ingress-controller traffic to the pods.
- On EKS, IRSA gives the pods S3 access for audit anchoring without static keys.
- The Angular UI is not in this image; serve its static build from a CDN or an nginx Deployment behind the same Ingress.
