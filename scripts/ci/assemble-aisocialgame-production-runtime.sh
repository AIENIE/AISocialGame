#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "${1:?repo}"&&pwd -P)";out="$(cd "${2:?bundle}"&&pwd -P)";fail(){ echo "AISocialGame production assembly failed: $*" >&2;exit 2;};[[ "$out" != /&&"$out" != "$repo"&&! -L "$out" ]]||fail unsafe
put(){ local s="$1" t="$2" m="$3";[[ -f "$s"&&! -L "$s"&&! -L "$t" ]]||fail "$s";mkdir -p "$(dirname "$t")";install -m "$m" "$s" "$t";}
if [[ ! -f "$out/backend/app.jar" || -L "$out/backend/app.jar" ]]; then
  jar_source="$(find "$out/backend/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' -print -quit 2>/dev/null || true)"
  [[ -n "$jar_source" && -f "$jar_source" && ! -L "$jar_source" ]] || fail 'flattened backend JAR is missing or unsafe'
  install -m 0444 -- "$jar_source" "$out/backend/app.jar"
fi
[[ -s "$out/backend/app.jar" && ! -L "$out/backend/app.jar" ]] || fail 'production backend JAR is empty or unsafe'
[[ -f "$out/frontend/dist/index.html" && ! -L "$out/frontend/dist/index.html" ]] || fail 'flattened frontend dist is missing or unsafe'
put "$repo/backend/start-production-backend.sh" "$out/backend/start-production-backend.sh" 0555
put "$repo/backend/runtime-process-environment.sh" "$out/backend/runtime-process-environment.sh" 0444
put "$repo/scripts/docker/production-load-env-file.sh" "$out/docker/production-load-env-file.sh" 0555
put "$repo/frontend/nginx.conf" "$out/frontend/nginx.conf" 0444
put "$repo/scripts/ci/aisocialgame-production-runtime-compose.yml" "$out/docker-compose.yml" 0444
mkdir -p "$out/release/sql"
cp "$repo/backend/sql/"*.sql "$repo/backend/sql/migrations.json" "$out/release/sql/"
mkdir -p "$out/scripts/config-pair"
cp -R "$repo/scripts/config-pair/." "$out/scripts/config-pair/"
install -m 0444 "$repo/scripts/config-pair/runtime-config-format" "$out/.aienie-runtime-config-format"
install -m 0444 "$repo/scripts/config-pair/runtime-grpc-auth-format" "$out/.aienie-runtime-grpc-auth-format"

python3 - "$out" <<'PYCONFIGMANIFEST'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]);path=root/'.project-manifest.json'
value=json.loads(path.read_text(encoding='utf-8'))
value['files']=sorted(p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() and p!=path)
path.chmod(0o600);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');path.chmod(0o444)
PYCONFIGMANIFEST

echo 'Assembled AISocialGame production runtime bundle; SQL and backups are managed manually.'
