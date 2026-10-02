#!/bin/sh
# Literal secret-file loader. YAML is loaded by Spring ConfigData.
set -eu
fail() { printf 'load-env-file: %s\n' "$*" >&2; exit 1; }
load_one_env_file() {
[ "$#" -eq 1 ] || fail 'expected exactly one secret file'
file="$1"
[ -f "$file" ] && [ ! -L "$file" ] || fail 'secret file must be a regular non-link file'
permissions=$(stat -c %a "$file") || fail 'cannot inspect secret-file permissions'
case "$permissions" in [0-7]00) ;; *) fail 'secret file must be owner-only' ;; esac
seen=' '
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in *"$(printf '\r')") line=${line%"$(printf '\r')"} ;; esac
  case "$line" in ''|'#'*) continue ;; esac
  case "$line" in export\ *) line=${line#export } ;; esac
  case "$line" in *=*) ;; *) fail 'invalid literal secret entry' ;; esac
  key=${line%%=*}
  value=${line#*=}
  case "$key" in ''|[0-9]*|*[!A-Za-z0-9_]*) fail 'invalid secret key' ;; esac
  case "$seen" in *" $key "*) fail "duplicate secret key: $key" ;; esac
  seen="$seen$key "
  case "$key" in
    JAVA_*|JDK_*|_JAVA_OPTIONS|MAVEN_*|PATH|CLASSPATH|BASH_ENV|LD_*|SPRING_CONFIG_*|SPRING_APPLICATION_JSON)
      fail "blocked process key: $key" ;;
  esac
  case "$key" in
    ENV|AUTH_MODE|AISERVICE_HMAC_SECRET|AI_HMAC_SECRET|AI_SERVICE_CALLER_SECRET|APP_EXTERNAL_AISERVICE_HMAC_SECRET|APP_EXTERNAL_AI_HMAC_SECRET|APP_EXTERNAL_PAYSERVICE_JWT|APP_EXTERNAL_PAYSERVICE_JWT_SECRET|APP_EXTERNAL_PAYSERVICE_TOKEN|APP_EXTERNAL_PAY_JWT_SECRET|APP_EXTERNAL_PAY_SERVICE_JWT|APP_EXTERNAL_PAY_TOKEN|APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN|APP_EXTERNAL_USERSERVICE_INTERNAL_TOKEN|APP_EXTERNAL_USERSERVICE_JWT_SECRET|APP_EXTERNAL_USER_INTERNAL_TOKEN|APP_EXTERNAL_USER_JWT_SECRET|APP_EXTERNAL_USER_TOKEN|BILLING_ONBOARDING_LEGACY_STATIC_TOKEN|BILLING_ONBOARDING_SERVICE_JWT_SECRET|EXTERNAL_AI_HMAC_SECRET|EXTERNAL_PAY_SERVICE_JWT|EXTERNAL_PAY_SERVICE_JWT_SECRET|EXTERNAL_PAY_TOKEN|EXTERNAL_USER_INTERNAL_GRPC_TOKEN|EXTERNAL_USER_INTERNAL_TOKEN|EXTERNAL_USER_SERVICE_JWT_SECRET|GRPCUI_AUTHORIZATION_HEADER|GRPC_AUTH_CALLERS|GRPC_INTERNAL_CLIENT_SERVICE_NAME|GRPC_INTERNAL_CLIENT_TOKEN_TTL_SECONDS|GRPC_JWT_SECRET|PAYSERVICE_JWT|PAYSERVICE_JWT_SECRET|PAYSERVICE_SERVICE_JWT|PAYSERVICE_TOKEN|PAY_SERVICE_JWT|SECURITY_GRPC_LEGACY_INTERNAL_TOKEN_ENABLED|USERSERVICE_INTERNAL_GRPC_TOKEN|USERSERVICE_JWT_SECRET|USERSERVICE_SERVICE_JWT_SECRET|GRPC_CALLER_*_SECRET|SECURITY_GRPC_SERVICE_JWT_CALLERS_*_SECRET) fail "obsolete or non-secret configuration key: $key" ;;
  esac
  case "$value" in
    \"*\") value=${value#\"}; value=${value%\"} ;;
    \'*\') value=${value#\'}; value=${value%\'} ;;
  esac
  export "$key=$value"
done < "$file"

}


case "$0" in *load-env-file.sh) load_one_env_file "$@" ;; esac
