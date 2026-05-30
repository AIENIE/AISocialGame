#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

COMPOSE_ARGS=(-f "$ROOT_DIR/docker-compose.yml")
if [[ -f "$ROOT_DIR/env.txt" ]]; then
  COMPOSE_ARGS=(--env-file "$ROOT_DIR/env.txt" "${COMPOSE_ARGS[@]}")
fi

docker compose "${COMPOSE_ARGS[@]}" up -d --build --remove-orphans
