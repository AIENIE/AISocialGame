# Source from Bash release scripts. Read YAML first and preserve literal secrets.
load_configuration_pair() {
  local configuration_root="$1" configuration_env="$2" key value
  python3 "$configuration_root/scripts/config-pair/shell_configuration.py" "$configuration_root" "$configuration_env" --validate || return 1
  . "$configuration_root/scripts/config-pair/load-env-file.sh"
  load_one_env_file "$configuration_env" || return 1
  while IFS= read -r -d '' key && IFS= read -r -d '' value; do
    printf -v "$key" '%s' "$value"
    export "$key"
  done < <(python3 "$configuration_root/scripts/config-pair/shell_configuration.py" "$configuration_root" "$configuration_env")
}
