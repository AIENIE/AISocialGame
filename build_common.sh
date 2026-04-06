#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

export CI=${CI:-true}
APP_DOMAIN_DEFAULT="${APP_DOMAIN_DEFAULT:-aisocialgame.seekerhut.com}"
APP_DOMAIN="${APP_DOMAIN:-$APP_DOMAIN_DEFAULT}"

step() {
  echo "== $1 =="
}

ensure_build_wrapper_sync() {
  step "Validate build wrapper scripts"
  local normalized_build normalized_prod
  normalized_build="$(sed -E 's#APP_DOMAIN_DEFAULT="[^"]+"#APP_DOMAIN_DEFAULT="<APP_DOMAIN_DEFAULT>"#' "$repo_root/build.sh")"
  normalized_prod="$(sed -E 's#APP_DOMAIN_DEFAULT="[^"]+"#APP_DOMAIN_DEFAULT="<APP_DOMAIN_DEFAULT>"#' "$repo_root/build_prod.sh")"
  if [[ "$normalized_build" != "$normalized_prod" ]]; then
    echo "build.sh and build_prod.sh differ beyond APP_DOMAIN_DEFAULT" >&2
    diff -u <(echo "$normalized_build") <(echo "$normalized_prod") || true
    exit 1
  fi
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
    if [[ -z "${!name:-}" ]]; then
      export "$name=$value"
    fi
  done < "$env_file"
}

load_env_file "$repo_root/env.txt"
ensure_build_wrapper_sync

export MYSQL_HOST="${MYSQL_HOST:-192.168.5.141}"
export MYSQL_PORT="${MYSQL_PORT:-13306}"
export MYSQL_DB="${MYSQL_DB:-aisocialgame}"
export MYSQL_ROOT_USERNAME="${MYSQL_ROOT_USERNAME:-root}"
export MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-password}"
export MYSQL_BOOTSTRAP_ENABLED="${MYSQL_BOOTSTRAP_ENABLED:-true}"
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-aisocialgame}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-aisocialgame_pwd}"
export SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-192.168.5.141}"
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-16379}"
export CONSUL_HTTP_ADDR="${CONSUL_HTTP_ADDR:-http://192.168.5.208:60001}"
export USER_GRPC_SERVICE_NAME="${USER_GRPC_SERVICE_NAME:-aienie-userservice-grpc}"
export BILLING_GRPC_SERVICE_NAME="${BILLING_GRPC_SERVICE_NAME:-aienie-payservice-grpc}"
export AI_GRPC_SERVICE_NAME="${AI_GRPC_SERVICE_NAME:-aienie-aiservice-grpc}"
export USER_GRPC_ADDR="${USER_GRPC_ADDR:-consul:///${USER_GRPC_SERVICE_NAME}}"
export BILLING_GRPC_ADDR="${BILLING_GRPC_ADDR:-consul:///${BILLING_GRPC_SERVICE_NAME}}"
export AI_GRPC_ADDR="${AI_GRPC_ADDR:-consul:///${AI_GRPC_SERVICE_NAME}}"
export QDRANT_HOST="${QDRANT_HOST:-http://192.168.5.141}"
export QDRANT_PORT="${QDRANT_PORT:-16333}"
export QDRANT_ENABLED="${QDRANT_ENABLED:-true}"
export SSO_USER_SERVICE_NAME="${SSO_USER_SERVICE_NAME:-aienie-userservice-http}"
export SSO_USER_SERVICE_BASE_URL="${SSO_USER_SERVICE_BASE_URL:-http://192.168.5.208:20000}"
export SSO_CALLBACK_URL="${SSO_CALLBACK_URL:-https://${APP_DOMAIN}/sso/callback}"
export SSO_LOGIN_PATH="${SSO_LOGIN_PATH:-/sso/login}"
export SSO_REGISTER_PATH="${SSO_REGISTER_PATH:-/register}"
export USER_SERVICE_BASE_URL="${USER_SERVICE_BASE_URL:-http://192.168.5.208:20000}"
export PAY_SERVICE_BASE_URL="${PAY_SERVICE_BASE_URL:-http://192.168.5.208:20020}"
export AI_SERVICE_BASE_URL="${AI_SERVICE_BASE_URL:-http://192.168.5.208:20010}"
export APP_EXTERNAL_GRPC_AUTH_REQUIRED="${APP_EXTERNAL_GRPC_AUTH_REQUIRED:-true}"

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

ensure_tcp_ready() {
  local host="$1"
  local port="$2"
  local name="$3"
  local tries=${4:-20}
  for i in $(seq 1 "$tries"); do
    if timeout 2 bash -c "</dev/tcp/${host}/${port}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Dependency ${name} is not reachable at ${host}:${port}" >&2
  return 1
}

check_external_dependencies() {
  step "Check external dependencies"
  ensure_tcp_ready "$MYSQL_HOST" "$MYSQL_PORT" "MySQL"
  ensure_tcp_ready "$SPRING_DATA_REDIS_HOST" "$SPRING_DATA_REDIS_PORT" "Redis"
  ensure_tcp_ready "$(echo "$QDRANT_HOST" | sed -E 's#https?://##')" "$QDRANT_PORT" "Qdrant"

  local consul_host consul_port
  consul_host="$(echo "$CONSUL_HTTP_ADDR" | sed -E 's#https?://##; s#/.*##; s#:.*$##')"
  consul_port="$(echo "$CONSUL_HTTP_ADDR" | sed -E 's#https?://##; s#/.*##; s#.*:##')"
  ensure_tcp_ready "$consul_host" "$consul_port" "Consul"
}

ensure_mysql_ready() {
  if [[ "${MYSQL_BOOTSTRAP_ENABLED}" != "true" ]]; then
    echo "Skip MySQL bootstrap (MYSQL_BOOTSTRAP_ENABLED=${MYSQL_BOOTSTRAP_ENABLED})"
    return 0
  fi

  step "Ensure MySQL database/user"
  docker run --rm --network host mysql:8.0 sh -c \
    "mysql --connect-timeout=10 --ssl-mode=DISABLED --get-server-public-key -h '${MYSQL_HOST}' -P '${MYSQL_PORT}' -u '${MYSQL_ROOT_USERNAME}' -p'${MYSQL_ROOT_PASSWORD}' <<'SQL'
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${SPRING_DATASOURCE_USERNAME}'@'%' IDENTIFIED BY '${SPRING_DATASOURCE_PASSWORD}';
ALTER USER '${SPRING_DATASOURCE_USERNAME}'@'%' IDENTIFIED BY '${SPRING_DATASOURCE_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DB}\`.* TO '${SPRING_DATASOURCE_USERNAME}'@'%';
FLUSH PRIVILEGES;
SQL"
}

run_migration() {
  if [[ "${RUN_FULL_MIGRATION:-true}" != "true" ]]; then
    echo "Skip full migration (RUN_FULL_MIGRATION=${RUN_FULL_MIGRATION:-false})"
    return 0
  fi

  step "Run full credit migration"
  local backend_url="http://127.0.0.1:${BACKEND_PORT:-11031}"
  local admin_username="${APP_ADMIN_USERNAME:-admin}"
  local admin_password="${APP_ADMIN_PASSWORD:-admin123}"
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
check_external_dependencies
ensure_mysql_ready
echo "Using shared services: MYSQL=${MYSQL_HOST}:${MYSQL_PORT} REDIS=${SPRING_DATA_REDIS_HOST}:${SPRING_DATA_REDIS_PORT} QDRANT=${QDRANT_HOST}:${QDRANT_PORT} CONSUL=${CONSUL_HTTP_ADDR}"
echo "External domains: USER=${USER_SERVICE_BASE_URL} PAY=${PAY_SERVICE_BASE_URL} AI=${AI_SERVICE_BASE_URL}"
echo "gRPC services via consul: user=${USER_GRPC_SERVICE_NAME} billing=${BILLING_GRPC_SERVICE_NAME} ai=${AI_GRPC_SERVICE_NAME}"
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
