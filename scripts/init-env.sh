#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ -e .env ]]; then
  echo "Reusing .env"
  exit 0
fi
tmp=$(mktemp .env.XXXXXX)
trap 'rm -f "$tmp"' EXIT
for key in POSTGRES_PASSWORD DB_PASSWORD REPL_PASSWORD SCALER_PASSWORD REDIS_EAST_PASSWORD REDIS_WEST_PASSWORD; do
  printf '%s=%s\n' "$key" "$(openssl rand -hex 24)"
done > "$tmp"
ln "$tmp" .env || { [[ -f .env ]] && exit 0; exit 1; }
echo "Created .env with local lab credentials"
