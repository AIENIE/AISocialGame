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
mkdir -p "$output_dir/scripts/config-pair"
cp -R "$repo_root/scripts/config-pair/." "$output_dir/scripts/config-pair/"
install -m 0444 "$repo_root/scripts/config-pair/runtime-config-format" "$output_dir/.aienie-runtime-config-format"
install -m 0444 "$repo_root/scripts/config-pair/runtime-grpc-auth-format" "$output_dir/.aienie-runtime-grpc-auth-format"

python3 - "$output_dir" <<'PYCONFIGMANIFEST'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]);path=root/'.project-manifest.json'
value=json.loads(path.read_text(encoding='utf-8'))
value['files']=sorted(p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() and p!=path)
path.chmod(0o600);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');path.chmod(0o444)
PYCONFIGMANIFEST
