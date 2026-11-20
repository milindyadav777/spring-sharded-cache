#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/init-env.sh
docker compose config -q
helm lint helm/catalog
helm template catalog helm/catalog >/dev/null
kubectl kustomize infra >/dev/null
docker compose up -d --build
python3 scripts/smoke.py
