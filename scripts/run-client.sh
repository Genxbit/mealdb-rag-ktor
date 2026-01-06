#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Load .env (export all vars)
if [[ -f ".env" ]]; then
  set -a
  source ".env"
  set +a
fi

INPUT="${*:-}"

if [[ -z "$INPUT" ]]; then
  echo "Usage:"
  echo "  ./scripts/run-client.sh \"chicken, lemon, garlic — quick, not spicy\""
  echo
  echo "Env:"
  echo "  MEALDB_SERVER_URL=${MEALDB_SERVER_URL:-http://localhost:8080}"
  echo "  MEALDB_MAX=${MEALDB_MAX:-}"
  exit 1
fi

echo "Calling server at: ${MEALDB_SERVER_URL:-http://localhost:8080}"
echo "MEALDB_MAX=${MEALDB_MAX:-}"
echo

./gradlew :cli:run --args="$INPUT" --console=plain
