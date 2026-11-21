#!/bin/sh
# prints one line every 10 s: time, deployment ready/desired, HPA targets, Prom active+queued sum, per-pod cpu
export PATH=/d/Dev/tmp-k8s-tools/bin:$PATH KUBECONFIG=/d/Dev/tmp-k8s-tools/keda/kubeconfig
Q='sum(reconcile_investigations_executor_active%7Bnamespace%3D%22reconcile%22%7D)%2Bsum(reconcile_investigations_executor_queued%7Bnamespace%3D%22reconcile%22%7D)'
while true; do
  t=$(date +%H:%M:%S)
  d=$(kubectl -n reconcile get deploy reconcile-ai -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null)
  h=$(kubectl -n reconcile get hpa keda-hpa-reconcile-ai --no-headers 2>/dev/null | awk '{print $3" "$4" "$5" "$6" "$7}')
  p=$(kubectl -n monitoring exec deploy/prometheus-server -- wget -qO- "http://localhost:9090/api/v1/query?query=$Q" 2>/dev/null | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)".*/\1/p')
  c=$(kubectl top pods -n reconcile --no-headers 2>/dev/null | grep reconcile-ai | awk '{printf "%s ", $2}')
  echo "$t deploy=$d hpa=[$h] promSum=${p:-none} podCPU=[$c]"
  sleep 10
done
