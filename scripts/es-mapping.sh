#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Load .env (export all vars)
if [[ -f ".env" ]]; then
  set -a
  source ".env"
  set +a
fi

curl -s "http://localhost:9200/meals-lab/_mapping?pretty"
echo
