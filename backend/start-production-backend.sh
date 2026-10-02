#!/bin/sh
set -eu
JAVA_HOME=/opt/java/openjdk; PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export JAVA_HOME PATH
[ -r /app/bin/runtime-process-environment.sh ] || { echo 'runtime process policy unavailable' >&2; exit 1; }
. /app/bin/runtime-process-environment.sh; clear_process_overrides
[ "$#" -eq 1 ] || { echo 'AISocialGame production launcher requires one env file' >&2; exit 64; }
[ -r /app/bin/production-load-env-file.sh ] || { echo 'production env loader unavailable' >&2; exit 1; }
. /app/bin/production-load-env-file.sh
load_one_env_file "$1"
[ -f /app/application.yml ] || exit 1
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/application.yml
export SPRING_PROFILES_ACTIVE=production
/opt/java/openjdk/bin/java -Dloader.main=com.aienie.configpair.ConfigurationPreflight -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher production
clear_process_overrides
[ -f /app/application.yml ] || { echo "runtime application.yml is missing" >&2; exit 1; }
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/application.yml
exec /opt/java/openjdk/bin/java -jar /app/app.jar
