#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="${1:-local}"

cd "$ROOT_DIR"

if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

if [ "$PROFILE" = "local" ]; then
  export DB_PASSWORD="${LOCAL_DB_PASSWORD:-rootpassword}"
fi

echo "Generating normalized exercise catalog from workbook..."
python3 scripts/generate_exercise_catalog.py

echo "Resetting exercise catalog with Spring Boot bootstrap runner (profile=${PROFILE})..."
APP_EXERCISE_CATALOG_MODE=reset \
APP_EXERCISE_CATALOG_RESOURCE=catalog/exercise-catalog.json \
APP_EXERCISE_CATALOG_EXIT_AFTER_BOOTSTRAP=true \
VECTOR_MIGRATION_ENABLED=false \
QDRANT_ENABLED=false \
./gradlew bootRun --args="--spring.profiles.active=${PROFILE} --spring.main.web-application-type=none"
