#!/usr/bin/env bash

set -euo pipefail

log_step() {
  printf '== %s ==\n' "$1"
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

load_env_file() {
  local path="$1"
  [[ -f "$path" ]] || return 0

  log_step "Load env file: $(basename "$path")"

  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    local line
    local name
    local value
    line="$(trim "$raw_line")"
    [[ -z "$line" || "$line" == \#* ]] && continue

    if [[ "$line" =~ ^(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]]; then
      name="${BASH_REMATCH[2]}"
      value="$(trim "${BASH_REMATCH[3]}")"

      if [[ ${#value} -ge 2 ]]; then
        if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
          value="${value:1:${#value}-2}"
        elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
          value="${value:1:${#value}-2}"
        fi
      fi

      if [[ ! -v "$name" ]]; then
        export "$name=$value"
      fi
    fi
  done < "$path"
}

assert_required_env() {
  local missing=()
  local name
  for name in "$@"; do
    if [[ -z "${!name:-}" ]]; then
      missing+=("$name")
    fi
  done

  if (( ${#missing[@]} > 0 )); then
    printf 'Missing required environment variables: %s\n' "${missing[*]}" >&2
    exit 1
  fi
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
backend_dir="$repo_root/backend"

cd "$repo_root"
load_env_file "$repo_root/env.txt"
load_env_file "$repo_root/env.local"

export SERVER_PORT="${SERVER_PORT:-${BACKEND_PORT:-11031}}"
export APP_DEMO_SEED_ENABLED="${APP_DEMO_SEED_ENABLED:-true}"

if [[ "${APP_EXTERNAL_GRPC_AUTH_REQUIRED:-true}" == "true" ]]; then
  assert_required_env \
    APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN \
    APP_EXTERNAL_PAYSERVICE_JWT \
    APP_EXTERNAL_AISERVICE_HMAC_CALLER \
    APP_EXTERNAL_AISERVICE_HMAC_SECRET
fi

assert_required_env SPRING_DATASOURCE_PASSWORD APP_ADMIN_PASSWORD

if [[ "${APP_SECURITY_ALLOW_WEAK_RUNTIME_DEFAULTS:-false}" != "true" ]]; then
  if [[ "${SPRING_DATASOURCE_PASSWORD:-}" == "aisocialgame""_pwd" || "${APP_ADMIN_PASSWORD:-}" == "admin""123" ]]; then
    printf 'Refusing to start with default database or admin passwords\n' >&2
    exit 1
  fi
  insecure_ssl_param="use""SSL" insecure_ssl_value="false"
  insecure_key_retrieval_param="allowPublicKey""Retrieval" insecure_key_retrieval_value="true"
  if [[ "${SPRING_DATASOURCE_URL:-}" == *"${insecure_ssl_param}=${insecure_ssl_value}"* || "${SPRING_DATASOURCE_URL:-}" == *"${insecure_key_retrieval_param}=${insecure_key_retrieval_value}"* ]]; then
    printf 'Refusing to start with insecure datasource URL\n' >&2
    exit 1
  fi
fi

log_step "Backend: spring-boot:run"
cd "$backend_dir"
exec mvn -DskipTests spring-boot:run
