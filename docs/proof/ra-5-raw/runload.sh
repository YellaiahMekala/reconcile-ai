#!/bin/sh
# usage: runload.sh MODE THREADS DURATION [PACE]
export PATH=/d/Dev/tmp-k8s-tools/bin:$PATH KUBECONFIG=/d/Dev/tmp-k8s-tools/keda/kubeconfig
R="D:/Interview_2026/AI_Projects/ReconcileAI_Project/ReconcileAI_Phase1/reconcile-ai/k8s/overlays/keda-kind-real/loadgen.yaml"
kubectl -n loadgen delete job loadgen --ignore-not-found --wait >/dev/null 2>&1
sed -e "s/\(name: MODE, value: \)\"investigate\"/\1\"$1\"/" -e "s/\(name: THREADS, value: \)\"8\"/\1\"$2\"/" -e "s/\(name: DURATION, value: \)\"300\"/\1\"$3\"/" -e "s/\(- { name: DURATION.*\)/\1\n            - { name: PACE, value: \"${4:-0}\" }/" $R | kubectl apply -f - >/dev/null
