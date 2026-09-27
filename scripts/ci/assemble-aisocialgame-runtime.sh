#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "${1:?missing repository root}" && pwd -P)"
output_dir="$(cd "${2:?missing flattened bundle}" && pwd -P)"

fail() {
  printf 'AISocialGame runtime bundle assembly failed: %s\n' "$*" >&2
  exit 2
}

[[ "$output_dir" != / && "$output_dir" != "$repo_root" && ! -L "$output_dir" ]] || fail 'unsafe output'
[[ -f "$output_dir/backend/app.jar" && ! -L "$output_dir/backend/app.jar" ]] || fail 'backend JAR missing'
[[ -d "$output_dir/frontend/dist" && ! -L "$output_dir/frontend/dist" ]] || fail 'frontend dist missing'

install_exact() {
  local source="$1" target="$2" mode="$3"
  [[ -f "$source" && ! -L "$source" ]] || fail "tracked runtime file missing: $source"
  mkdir -p -- "$(dirname "$target")"
  install -m "$mode" -- "$source" "$target"
}

install_exact "$repo_root/backend/start-backend.sh" "$output_dir/backend/start-backend.sh" 0555
install_exact "$repo_root/backend/runtime-process-environment.sh" "$output_dir/backend/runtime-process-environment.sh" 0444
install_exact "$repo_root/scripts/docker/staging-load-env-file.sh" "$output_dir/scripts/docker/staging-load-env-file.sh" 0555
install_exact "$repo_root/frontend/nginx.conf" "$output_dir/frontend/nginx.conf" 0444
install_exact "$repo_root/scripts/ci/aisocialgame-runtime-compose.yml" "$output_dir/docker-compose.yml" 0444
install_exact "$repo_root/scripts/ci/staging-oci-role-contract.json" "$output_dir/release/staging-oci-role-contract.json" 0444

python3 "$repo_root/scripts/ci/verify-staging-oci-role-contract.py" \
  "$output_dir/release/staging-oci-role-contract.json" "$output_dir/docker-compose.yml" ai-social-game

grep -Fq '127.0.0.1:11031:20030' "$output_dir/docker-compose.yml" || fail 'listener mapping drifted'
grep -Fq 'AISOCIALGAME_BACKEND_IMAGE' "$output_dir/docker-compose.yml" || fail 'backend image closure missing'
grep -Fq 'AISOCIALGAME_FRONTEND_IMAGE' "$output_dir/docker-compose.yml" || fail 'frontend image closure missing'
grep -Fq '/run/aienie/trust/staging-root.pem' "$output_dir/docker-compose.yml" || fail 'staging trust mount missing'
grep -Fq 'source: /etc/aienie-staging-pki/root.pem' "$output_dir/docker-compose.yml" || fail 'fixed staging trust source missing'
grep -Fq 'create_host_path: false' "$output_dir/docker-compose.yml" || fail 'staging trust bind is not fail-closed'
grep -Fq 'entrypoint: ["/app/bin/start-backend.sh"]' "$output_dir/docker-compose.yml" || fail 'reviewed launcher missing'
grep -Fq 'command: ["/app/env.txt"]' "$output_dir/docker-compose.yml" || fail 'fixed runtime env input missing'
grep -Fq 'http://127.0.0.1:20030/actuator/health' "$output_dir/docker-compose.yml" || fail 'strict backend health gate missing'
grep -Fq 'condition: service_healthy' "$output_dir/docker-compose.yml" || fail 'frontend readiness gate missing'
! grep -Eq 'JAVA_OPTS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS' "$output_dir/docker-compose.yml" || fail 'JVM option channel leaked'
! grep -Eq '(^|[[:space:]])build:|env_file:|/home/|extra_hosts|host-gateway|sh[[:space:]]+-c|\$\{[^}]*(PORT|HOST|ROOT)[^}]*\}' "$output_dir/docker-compose.yml" || fail 'local, ambient, or mutable runtime authority leaked'

python3 - "$output_dir" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = root / ".project-manifest.json"
if manifest.exists():
    value = json.loads(manifest.read_text(encoding="utf-8"))
    value["files"] = sorted(
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and path != manifest
    )
    manifest.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
PY
