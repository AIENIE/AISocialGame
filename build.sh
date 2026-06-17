#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

export CI=${CI:-true}
APP_DOMAIN_DEFAULT="${APP_DOMAIN_DEFAULT:-aisocialgame.localhut.com}"
APP_DOMAIN="${APP_DOMAIN:-$APP_DOMAIN_DEFAULT}"

step() {
  echo "== $1 =="
}

ensure_pnpm() {
  corepack enable >/dev/null 2>&1 || true
}

load_env_file() {
  local env_file="$1"
  if [[ ! -f "$env_file" ]]; then
    return
  fi

  step "Load env file: $(basename "$env_file")"
  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    local line="$raw_line"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    if [[ -z "$line" || "$line" == \#* ]]; then
      continue
    fi

    if [[ "$line" == export[[:space:]]* ]]; then
      line="${line#export }"
      line="${line#"${line%%[![:space:]]*}"}"
    fi

    if [[ "$line" != *=* ]]; then
      continue
    fi

    local name="${line%%=*}"
    local value="${line#*=}"
    name="${name%"${name##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"

    if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      continue
    fi
    if [[ ( "$value" == \"*\" && "$value" == *\" ) || ( "$value" == \'*\' && "$value" == *\' ) ]]; then
      value="${value:1:${#value}-2}"
    fi
    export "$name=$value"
  done < "$env_file"
}

load_env_file "$repo_root/env.txt"
load_env_file "$repo_root/env.local"

export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://base.seekerhut.com:3306/aisocialgame?useSSL=true&requireSSL=true&serverTimezone=UTC}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-aisocialgame}"
export SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-base.seekerhut.com}"
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-26379}"
export USER_GRPC_ADDR="${USER_GRPC_ADDR:-static://userservice:10001}"
export BILLING_GRPC_ADDR="${BILLING_GRPC_ADDR:-static://payservice.localhut.com:10021}"
export AI_GRPC_ADDR="${AI_GRPC_ADDR:-static://aiservice.localhut.com:10011}"
export USER_GRPC_NEGOTIATION_TYPE="${USER_GRPC_NEGOTIATION_TYPE:-PLAINTEXT}"
export BILLING_GRPC_NEGOTIATION_TYPE="${BILLING_GRPC_NEGOTIATION_TYPE:-PLAINTEXT}"
export AI_GRPC_NEGOTIATION_TYPE="${AI_GRPC_NEGOTIATION_TYPE:-PLAINTEXT}"
export QDRANT_HOST="${QDRANT_HOST:-http://base.seekerhut.com}"
export QDRANT_PORT="${QDRANT_PORT:-26333}"
export QDRANT_ENABLED="${QDRANT_ENABLED:-true}"
export SSO_USER_SERVICE_BASE_URL="${SSO_USER_SERVICE_BASE_URL:-https://userservice.localhut.com}"
export SSO_CALLBACK_URL="${SSO_CALLBACK_URL:-https://${APP_DOMAIN}/sso/callback}"
export SSO_LOGIN_PATH="${SSO_LOGIN_PATH:-/sso/login}"
export SSO_REGISTER_PATH="${SSO_REGISTER_PATH:-/register}"
export USER_SERVICE_BASE_URL="${USER_SERVICE_BASE_URL:-https://userservice.localhut.com}"
export PAY_SERVICE_BASE_URL="${PAY_SERVICE_BASE_URL:-https://payservice.localhut.com}"
export AI_SERVICE_BASE_URL="${AI_SERVICE_BASE_URL:-https://aiservice.localhut.com}"
export APP_EXTERNAL_GRPC_AUTH_REQUIRED="${APP_EXTERNAL_GRPC_AUTH_REQUIRED:-true}"
export APP_SECURITY_ALLOW_WEAK_RUNTIME_DEFAULTS="${APP_SECURITY_ALLOW_WEAK_RUNTIME_DEFAULTS:-false}"
export APP_SECURITY_ALLOW_PLAINTEXT_GRPC="${APP_SECURITY_ALLOW_PLAINTEXT_GRPC:-true}"

if [[ "$SPRING_DATASOURCE_URL" == jdbc:mysql://base.seekerhut.com:3306/* ]]; then
  export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL/:3306/:23306}"
  echo "Rewrote SPRING_DATASOURCE_URL to use base.seekerhut.com:23306 for Docker deployment"
fi

if [[ "${SPRING_DATA_REDIS_HOST}" == "base.seekerhut.com" && "${SPRING_DATA_REDIS_PORT}" == "6379" ]]; then
  export SPRING_DATA_REDIS_PORT="26379"
  echo "Rewrote SPRING_DATA_REDIS_PORT to use base.seekerhut.com:26379 for Docker deployment"
fi

if [[ "${QDRANT_HOST}" == "http://base.seekerhut.com" && "${QDRANT_PORT}" == "6333" ]]; then
  export QDRANT_PORT="26333"
  echo "Rewrote QDRANT_PORT to use base.seekerhut.com:26333 for Docker deployment"
fi

require_env_vars() {
  local missing=()
  for var_name in "$@"; do
    if [[ -z "${!var_name:-}" ]]; then
      missing+=("$var_name")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    echo "Missing required environment variables: ${missing[*]}" >&2
    exit 1
  fi
}

if [[ "$APP_EXTERNAL_GRPC_AUTH_REQUIRED" == "true" ]]; then
  require_env_vars \
    APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN \
    APP_EXTERNAL_PAYSERVICE_JWT \
    APP_EXTERNAL_AISERVICE_HMAC_CALLER \
    APP_EXTERNAL_AISERVICE_HMAC_SECRET
fi

require_env_vars SPRING_DATASOURCE_PASSWORD APP_ADMIN_PASSWORD

if [[ "$APP_SECURITY_ALLOW_WEAK_RUNTIME_DEFAULTS" != "true" ]]; then
  if [[ "$SPRING_DATASOURCE_PASSWORD" == "aisocialgame""_pwd" || "${APP_ADMIN_PASSWORD:-}" == "admin""123" ]]; then
    echo "Refusing to deploy with default database or admin passwords" >&2
    exit 1
  fi
  insecure_ssl_param="use""SSL" insecure_ssl_value="false"
  insecure_key_retrieval_param="allowPublicKey""Retrieval" insecure_key_retrieval_value="true"
  if [[ "$SPRING_DATASOURCE_URL" == *"${insecure_ssl_param}=${insecure_ssl_value}"* || "$SPRING_DATASOURCE_URL" == *"${insecure_key_retrieval_param}=${insecure_key_retrieval_value}"* ]]; then
    echo "Refusing to deploy with insecure datasource URL" >&2
    exit 1
  fi
fi

if [[ "$APP_SECURITY_ALLOW_PLAINTEXT_GRPC" != "true" ]]; then
  if [[ "$USER_GRPC_NEGOTIATION_TYPE" == "PLAINTEXT" || "$BILLING_GRPC_NEGOTIATION_TYPE" == "PLAINTEXT" || "$AI_GRPC_NEGOTIATION_TYPE" == "PLAINTEXT" ]]; then
    echo "Refusing to deploy with PLAINTEXT gRPC unless APP_SECURITY_ALLOW_PLAINTEXT_GRPC=true" >&2
    exit 1
  fi
fi

docker_compose_cmd() {
  if command -v docker-compose >/dev/null 2>&1; then
    echo "docker-compose"
  else
    echo "docker compose"
  fi
}

wait_for_http() {
  local url="$1"
  local tries=${2:-60}
  local delay=${3:-2}
  for i in $(seq 1 "$tries"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$delay"
  done
  echo "Service $url not ready after $tries attempts" >&2
  return 1
}

run_migration() {
  if [[ "${RUN_FULL_MIGRATION:-true}" != "true" ]]; then
    echo "Skip full migration (RUN_FULL_MIGRATION=${RUN_FULL_MIGRATION:-false})"
    return 0
  fi

  step "Run full credit migration"
  local backend_url="http://127.0.0.1:${BACKEND_PORT:-11031}"
  local admin_username="${APP_ADMIN_USERNAME:-admin}"
  local admin_password="${APP_ADMIN_PASSWORD}"
  local login_response token migrate_response failed_count

  login_response="$(curl -fsS -X POST "${backend_url}/api/admin/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${admin_username}\",\"password\":\"${admin_password}\"}")"

  token="$(echo "$login_response" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  if [[ -z "$token" ]]; then
    echo "Unable to acquire admin token for migration" >&2
    echo "$login_response" >&2
    return 1
  fi

  migrate_response="$(curl -fsS -X POST "${backend_url}/api/admin/billing/migrate-all" \
    -H 'Content-Type: application/json' \
    -H "X-Admin-Token: ${token}" \
    -d "{\"batchSize\":${MIGRATION_BATCH_SIZE:-100}}")"
  echo "$migrate_response"

  failed_count="$(echo "$migrate_response" | sed -n 's/.*"failed"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  if [[ -n "$failed_count" && "$failed_count" != "0" ]]; then
    echo "Migration reported failures: ${failed_count}" >&2
    return 1
  fi
}

step "Backend: test & package"
(
  cd backend
  env -u SPRING_DATASOURCE_URL \
      -u SPRING_DATASOURCE_USERNAME \
      -u SPRING_DATASOURCE_PASSWORD \
      -u SPRING_DATASOURCE_DRIVER_CLASS_NAME \
      mvn clean test package
)

step "Frontend: install & build"
(
  cd frontend
  ensure_pnpm
  pnpm install --frozen-lockfile
  pnpm build
)

step "Docker compose pull & restart"
COMPOSE="$(docker_compose_cmd)"
echo "Using external services: datasource=${SPRING_DATASOURCE_URL} redis=${SPRING_DATA_REDIS_HOST}:${SPRING_DATA_REDIS_PORT} qdrant=${QDRANT_HOST}:${QDRANT_PORT}"
echo "External domains: USER=${USER_SERVICE_BASE_URL} PAY=${PAY_SERVICE_BASE_URL} AI=${AI_SERVICE_BASE_URL}"
echo "gRPC targets: user=${USER_GRPC_ADDR} billing=${BILLING_GRPC_ADDR} ai=${AI_GRPC_ADDR}"
$COMPOSE down -v || true
$COMPOSE pull
$COMPOSE up -d

step "Wait for services"
export FRONTEND_PORT="${FRONTEND_PORT:-11030}"
export BACKEND_PORT="${BACKEND_PORT:-11031}"
wait_for_http "http://127.0.0.1:${FRONTEND_PORT}" 60
wait_for_http "http://127.0.0.1:${BACKEND_PORT}/actuator/health" 60

run_migration

echo "All done. Frontend: https://${APP_DOMAIN}  Backend API: https://${APP_DOMAIN}/api"
