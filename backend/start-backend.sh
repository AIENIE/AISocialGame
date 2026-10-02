#!/bin/sh
set -eu

JAVA_HOME=/opt/java/openjdk
PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME PATH

[ -r /app/bin/runtime-process-environment.sh ] || {
  echo "Canonical runtime process environment policy is unavailable" >&2
  exit 1
}
# shellcheck source=/dev/null
. /app/bin/runtime-process-environment.sh

clear_process_overrides

if [ "$#" -gt 0 ]; then
  [ -r /app/bin/staging-load-env-file.sh ] || {
    echo "Canonical runtime env loader is unavailable" >&2
    exit 1
  }
  # shellcheck source=/dev/null
  . /app/bin/staging-load-env-file.sh
  load_one_env_file "$@"
fi

JAVA_HOME=/opt/java/openjdk
PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME PATH
clear_process_overrides

[ -f /app/application.yml ] || { echo "runtime application.yml is missing" >&2; exit 1; }
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/application.yml
exec /opt/java/openjdk/bin/java -jar /app/app.jar
