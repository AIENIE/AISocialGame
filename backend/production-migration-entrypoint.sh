#!/bin/sh
set -eu
umask 077
PATH=/opt/java/openjdk/bin:/usr/bin:/bin
JAVA_HOME=/opt/java/openjdk
export PATH JAVA_HOME

[ "$#" -eq 1 ] || { echo 'ai-social-game migration action is required' >&2; exit 64; }
case "$1" in checkpoint|precheck|execute|reconcile) ;; *) echo 'ai-social-game migration action is invalid' >&2; exit 64;; esac
migration_action=$1
[ -r /app/bin/production-load-env-file.sh ] || { echo 'ai-social-game migration env loader is unavailable' >&2; exit 1; }
set -- /app/env.txt
. /app/bin/production-load-env-file.sh
load_one_env_file /app/env.txt
[ -f /app/application.yml ] || exit 1
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/application.yml
unset JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS MAVEN_OPTS MAVEN_ARGS \
export SPRING_PROFILES_ACTIVE=production
/opt/java/openjdk/bin/java -Dloader.main=com.aienie.configpair.ConfigurationPreflight -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher production
[ -n "${SPRING_DATASOURCE_PASSWORD:-}" ] || { echo 'ai-social-game database credential is unavailable' >&2; exit 1; }

  CLASSPATH BASH_ENV CDPATH LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT \
  HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY SSL_CERT_FILE SSL_CERT_DIR HOSTALIASES
export AIENIE_SOCIAL_MIGRATION_LEDGER=/app/release/migrations/sql-ledger.json
export AIENIE_SOCIAL_MIGRATION_PLAN=/app/release/migrations/production-plan.json

exec /opt/java/openjdk/bin/java \
  -Dloader.main=com.aisocialgame.migration.ProductionSocialMigrationMain \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$migration_action"
