#!/bin/sh
set -eu

load_one_env_file() {
  env_file="$1"
  [ -f "$env_file" ] || return 0

  while IFS= read -r raw_line || [ -n "$raw_line" ]; do
    line=$(printf '%s' "$raw_line" | sed 's/$//')
    trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//')

    case "$trimmed" in
      ''|'#'*) continue ;;
    esac

    case "$trimmed" in
      export\ *) trimmed=${trimmed#export } ;;
    esac

    case "$trimmed" in
      *=*) ;;
      *) continue ;;
    esac

    key=${trimmed%%=*}
    value=${trimmed#*=}
    key=$(printf '%s' "$key" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    value=$(printf '%s' "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')

    case "$key" in
      ''|[0-9]*|*[!A-Za-z0-9_]*)
        echo "Invalid env key in $env_file: $key" >&2
        exit 1
        ;;
    esac

    if [ -n "$value" ]; then
      first=${value%${value#?}}
      last=${value#${value%?}}
      if { [ "$first" = '"' ] && [ "$last" = '"' ]; } || { [ "$first" = "'" ] && [ "$last" = "'" ]; }; then
        value=${value#?}
        value=${value%?}
      fi
    fi

    export "$key=$value"
  done < "$env_file"
}

if [ "$#" -eq 0 ]; then
  set -- /app/env.txt
fi

for env_file in "$@"; do
  load_one_env_file "$env_file"
done
