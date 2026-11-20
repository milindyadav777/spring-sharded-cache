#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
namespace=catalog-lab
k=(kubectl --context "kind-${KIND_CLUSTER_NAME:-spring-labs}" -n "$namespace")
"${k[@]}" wait --for=condition=Ready scaledobject/catalog-worker --timeout=180s
"${k[@]}" port-forward svc/catalog 8081:8080 >/dev/null &
port_forward=$!
trap 'kill "$port_forward" 2>/dev/null || true' EXIT
base=http://127.0.0.1:8081
curl -fsS --retry 25 --retry-all-errors --retry-delay 1 "$base/actuator/health/readiness" >/dev/null
sku="kind-$(date +%s)"
path="$base/tenants/43/products/$sku"
curl -fsS -X PUT -H 'Content-Type: application/json' -d '{"name":"kind mug"}' "$path" | jq -e '.version == 1'
for region in EAST WEST; do
  found=false
  for i in {1..120}; do
    if curl -fsS -D - -o /dev/null "$path?region=$region" | tr -d '\r' | grep -iq "^X-Read-Source: redis-$region$"; then
      found=true
      break
    fi
    sleep 2
  done
  if [[ "$found" != true ]]; then echo "Missing cache hit from $region" >&2; exit 1; fi
done
echo "PASS real Kubernetes write and regional cache reads"
