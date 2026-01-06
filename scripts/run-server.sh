#!/usr/bin/env bash
# set -euo pipefail

# Move to repo root (parent of /scripts)
cd "$(dirname "$0")/.."

# Load .env (export all vars)
if [[ -f ".env" ]]; then
  set -a
  source ".env"
  set +a
fi

echo "Starting server..."
echo "ES_URL=${ES_URL:-}"
echo "OLLAMA_URL=${OLLAMA_URL:-}"
echo "OLLAMA_MODEL=${OLLAMA_MODEL:-}"
echo

./gradlew :server:run --console=plain
