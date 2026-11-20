#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Use a dedicated named kind context for all writes.
cluster="${KIND_CLUSTER_NAME:-spring-labs}"
ctx="kind-$cluster"
namespace=catalog-lab
if ! kind get clusters | grep -Fxq "$cluster"; then kind create cluster --name "$cluster"; fi
k=(kubectl --context "$ctx" -n "$namespace")
h=(helm --kube-context "$ctx")
./scripts/init-env.sh
docker build -t spring-sharded-cache:local .
kind load docker-image --name "$cluster" spring-sharded-cache:local
kubectl --context "$ctx" create namespace "$namespace" --dry-run=client -o yaml | kubectl --context "$ctx" apply -f -
"${k[@]}" create secret generic lab-credentials --from-env-file=.env --dry-run=client -o yaml | "${k[@]}" apply -f -
if ! kubectl --context "$ctx" get crd scaledobjects.keda.sh >/dev/null 2>&1; then
  helm repo add kedacore https://kedacore.github.io/charts --force-update
  "${h[@]}" upgrade --install keda kedacore/keda --version 2.18.0 -n keda --create-namespace --wait --timeout 10m
fi
"${k[@]}" apply -k infra
"${k[@]}" wait --for=condition=complete job/east-init job/west-init --timeout=12m
for role in shard0-primary shard1-primary shard0-replica shard1-replica; do
  "${k[@]}" rollout status "statefulset/$role" --timeout=8m
done
"${h[@]}" upgrade --install catalog helm/catalog -n "$namespace" --wait --timeout 8m
echo "kubectl --context $ctx -n $namespace port-forward svc/catalog 8081:8080"
