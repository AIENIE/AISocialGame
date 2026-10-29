#!/usr/bin/env bash
set -euo pipefail

# Repository-owned two-phase dependency/build contract used by the Aienie
# release platform.  The caller must declare the module arrays before calling
# aienie_ci_prepare_phase.


aienie_ci_fail() {
  printf 'Aienie repository CI contract failed: %s\n' "$*" >&2
  exit 2
}

aienie_ci_need() {
  command -v "$1" >/dev/null 2>&1 || aienie_ci_fail "missing command: $1"
}

aienie_ci_require_value() {
  local name="$1"
  [[ -n "${!name:-}" ]] || aienie_ci_fail "$name is required"
  [[ "${!name}" != *$'\n'* && "${!name}" != *$'\r'* ]] || aienie_ci_fail "$name contains a newline"
}

aienie_ci_assert_safe_directory() {
  local path="$1" label="$2" canonical
  canonical="$(realpath -m -- "$path")"
  [[ "$canonical" != "/" && "$canonical" != "$AIENIE_CI_REPO_ROOT" ]] || aienie_ci_fail "unsafe $label path: $canonical"
  case "$canonical/" in
    "$AIENIE_CI_REPO_ROOT/"*) aienie_ci_fail "$label must be outside the source checkout: $canonical" ;;
  esac
  [[ ! -L "$path" ]] || aienie_ci_fail "$label must not be a symbolic link"
  printf '%s\n' "$canonical"
}

aienie_ci_assert_empty_output() {
  local output="$1"
  if [[ -e "$output" ]]; then
    [[ -d "$output" && ! -L "$output" ]] || aienie_ci_fail 'AIENIE_CI_OUTPUT_DIR must be a regular directory'
    [[ -z "$(find "$output" -mindepth 1 -maxdepth 1 -print -quit)" ]] || aienie_ci_fail 'AIENIE_CI_OUTPUT_DIR must be empty'
  else
    mkdir -p -- "$output"
  fi
}

aienie_ci_assert_manifest_path() {
  local expected
  expected="$AIENIE_CI_OUTPUT_DIR/repository-dependency-manifest.json"
  [[ "$AIENIE_DEPENDENCY_MANIFEST" == "$expected" ]] ||
    aienie_ci_fail "AIENIE_DEPENDENCY_MANIFEST must be the platform output file: $expected"
}

aienie_ci_assert_build_output() {
  [[ -d "$AIENIE_CI_OUTPUT_DIR" && ! -L "$AIENIE_CI_OUTPUT_DIR" ]] ||
    aienie_ci_fail 'build output must be a regular directory preloaded by the platform'
  [[ -f "$AIENIE_DEPENDENCY_MANIFEST" && ! -L "$AIENIE_DEPENDENCY_MANIFEST" ]] ||
    aienie_ci_fail 'build dependency manifest is missing or unsafe'
  [[ -z "$(find "$AIENIE_CI_OUTPUT_DIR" -mindepth 1 -maxdepth 1 ! -name repository-dependency-manifest.json -print -quit)" ]] ||
    aienie_ci_fail 'build output must initially contain only repository-dependency-manifest.json'
}

aienie_ci_assert_no_special_entries() {
  local root="$1" label="$2"
  [[ -z "$(find "$root" -type l -print -quit)" ]] || aienie_ci_fail "$label contains a symbolic link"
  [[ -z "$(find "$root" ! -type d ! -type f -print -quit)" ]] || aienie_ci_fail "$label contains a special filesystem entry"
}

aienie_ci_write_specs() {
  local output="$1" kind module
  : >"$output"
  for module in "${AIENIE_CI_MAVEN_MODULES[@]}"; do printf 'maven\t%s\n' "$module" >>"$output"; done
  for module in "${AIENIE_CI_PNPM_MODULES[@]}"; do printf 'pnpm\t%s\n' "$module" >>"$output"; done
  [[ -s "$output" ]] || aienie_ci_fail 'no CI modules were declared'
  while IFS=$'\t' read -r kind module; do
    [[ "$module" != /* && "$module" != *'..'* ]] || aienie_ci_fail "unsafe $kind module path: $module"
    [[ -d "$AIENIE_CI_REPO_ROOT/$module" ]] || aienie_ci_fail "missing $kind module directory: $module"
    case "$kind" in
      maven) [[ -f "$AIENIE_CI_REPO_ROOT/$module/pom.xml" ]] || aienie_ci_fail "missing $module/pom.xml" ;;
      pnpm) [[ -f "$AIENIE_CI_REPO_ROOT/$module/package.json" && -f "$AIENIE_CI_REPO_ROOT/$module/pnpm-lock.yaml" ]] || aienie_ci_fail "missing pnpm lock inputs under $module" ;;
    esac
  done <"$output"
}

aienie_ci_toolchains() {
  local output="$1"
  : >"$output"
  if (( ${#AIENIE_CI_MAVEN_MODULES[@]} > 0 )); then
    printf 'maven\t%s\n' "$(MAVEN_OPTS='-Djansi.force=false' mvn --version | head -n 1 | tr -d '\r')" >>"$output"
    printf 'java\t%s\n' "$(java -version 2>&1 | head -n 1 | tr -d '\r')" >>"$output"
  fi
  if (( ${#AIENIE_CI_PNPM_MODULES[@]} > 0 )); then
    printf 'node\t%s\n' "$(node --version)" >>"$output"
    printf 'pnpm\t%s\n' "$(pnpm --version)" >>"$output"
  fi
  sort -u -o "$output" "$output"
}

aienie_ci_cache_inventory() {
  python3 - "$1" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
entries = []
for path in sorted((p for p in root.rglob('*') if p.is_file()), key=lambda p: p.relative_to(root).as_posix()):
    relative = path.relative_to(root).as_posix()
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    entries.append((relative, digest))
h = hashlib.sha256()
for relative, digest in entries:
    h.update(relative.encode('utf-8')); h.update(b'\0'); h.update(digest.encode('ascii')); h.update(b'\n')
print(len(entries), h.hexdigest())
PY
}

aienie_ci_restore_manifest() {
  local staged="${AIENIE_CI_STAGED_MANIFEST:-}" destination="${AIENIE_CI_MANIFEST_PATH:-}"
  [[ -n "$staged" && -f "$staged" && -n "$destination" ]] || return 0
  mkdir -p -- "$(dirname "$destination")"
  if [[ -e "$destination" || -L "$destination" ]]; then
    [[ -f "$destination" || -L "$destination" ]] || return 1
    rm -f -- "$destination"
  fi
  mv -- "$staged" "$destination"
}

aienie_ci_cleanup() {
  local scratch="${AIENIE_CI_SCRATCH_DIR:-}"
  aienie_ci_restore_manifest || true
  if [[ -n "$scratch" && -d "$scratch" && "$scratch" != "/" && "$scratch" != "$AIENIE_CI_REPO_ROOT" ]]; then
    rm -rf -- "$scratch"
  fi
  AIENIE_CI_SCRATCH_DIR=''
}

aienie_ci_detach_manifest() {
  AIENIE_CI_MANIFEST_PATH="$AIENIE_DEPENDENCY_MANIFEST"
  AIENIE_CI_STAGED_MANIFEST="$AIENIE_CI_SCRATCH_DIR/repository-dependency-manifest.json"
  [[ ! -e "$AIENIE_CI_STAGED_MANIFEST" ]] || aienie_ci_fail 'internal staged manifest path already exists'
  mv -- "$AIENIE_CI_MANIFEST_PATH" "$AIENIE_CI_STAGED_MANIFEST"
}

aienie_ci_prepare_working_cache() {
  local working="$AIENIE_CI_SCRATCH_DIR/working-cache"
  mkdir -p -- "$working"
  cp -a -- "$AIENIE_CI_INPUT_CACHE_DIR/." "$working/"
  chmod -R u+rwX -- "$working"
  AIENIE_CI_CACHE_DIR="$working"
  export AIENIE_CI_CACHE_DIR
}

aienie_ci_resolve_maven() {
  local module
  mkdir -p -- "$AIENIE_CI_CACHE_DIR/maven"
  for module in "${AIENIE_CI_MAVEN_MODULES[@]}"; do
    (
      cd "$AIENIE_CI_REPO_ROOT/$module"
      mvn -B -ntp -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" \
        dependency:go-offline dependency:resolve dependency:resolve-plugins
    )
    # Materialize the effective POM once so plugin-managed test dependencies
    # are resolved from the repository's actual dependency management rather
    # than guessed versions. Maven's go-offline goal does not reliably include
    # the provider used by the configured Surefire plugin.
    local effective_pom
    effective_pom="$AIENIE_CI_SCRATCH_DIR/effective-pom-${module//\//_}.xml"
    (
      cd "$AIENIE_CI_REPO_ROOT/$module"
      mvn -B -ntp -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" \
        help:effective-pom -Doutput="$effective_pom" >/dev/null
    )

    if grep -q '<artifactId>[[:space:]]*protobuf-maven-plugin[[:space:]]*</artifactId>' \
      "$AIENIE_CI_REPO_ROOT/$module/pom.xml"; then
      local resolver classifier
      case "$(uname -m)" in
        x86_64|amd64) classifier='linux-x86_64' ;;
        aarch64|arm64) classifier='linux-aarch_64' ;;
        *) aienie_ci_fail "unsupported native classifier for protobuf tools: $(uname -m)" ;;
      esac
      resolver="${AIENIE_CI_PROTOBUF_TOOL_RESOLVER:-/opt/aienie-ci/resolve_maven_protobuf_tools.py}"
      [[ -f "$resolver" && ! -L "$resolver" ]] || aienie_ci_fail "protobuf tool resolver is missing or unsafe: $resolver"
      mapfile -t protobuf_artifacts < <(python3 "$resolver" "$effective_pom" "$classifier")
      (( ${#protobuf_artifacts[@]} == 2 )) || aienie_ci_fail "protobuf tool resolver returned an unexpected artifact set for $module"
      local artifact
      for artifact in "${protobuf_artifacts[@]}"; do
        mvn -B -ntp -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" \
          dependency:get -Dtransitive=true -Dartifact="$artifact"
      done
    fi

    local launcher_version
    launcher_version="$(python3 - "$effective_pom" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

root = ET.parse(pathlib.Path(sys.argv[1])).getroot()

def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]

def children(element):
    return {local_name(child.tag): (child.text or "").strip()
            for child in list(element)}

property_values = {}
for element in root.iter():
    if local_name(element.tag) == "properties":
        for child in list(element):
            value = (child.text or "").strip()
            if value:
                property_values[local_name(child.tag)] = value

for element in root.iter():
    if local_name(element.tag) != "dependency":
        continue
    values = children(element)
    if (values.get("groupId") == "org.junit.platform"
            and values.get("artifactId") == "junit-platform-launcher"
            and values.get("version")
            and "${" not in values["version"]):
        print(values["version"])
        raise SystemExit(0)

for key in ("junit-platform.version", "junit.platform.version"):
    value = property_values.get(key, "")
    if value and "${" not in value:
        print(value)
        raise SystemExit(0)
raise SystemExit("effective POM does not expose junit-platform-launcher version")
PY
)"
    [[ "$launcher_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] ||
      aienie_ci_fail "could not resolve junit-platform-launcher version for $module"

    # Warm both Surefire providers used by Aienie and the exact JUnit Platform
    # launcher selected by the effective POM while repository access is still
    # allowed; the sealed build phase then runs the same tests fully offline.
      mvn -B -ntp -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" \
      dependency:get -Dtransitive=true \
      -Dartifact="org.junit.platform:junit-platform-launcher:${launcher_version}"

    # Resolve the complete test class path without running tests.  Maven's
    # dependency plugin can omit provider-transitive artifacts (for example
    # junit-platform-launcher), while the test lifecycle resolves them
    # authoritatively.
    (cd "$AIENIE_CI_REPO_ROOT/$module" && mvn -B -ntp \
      -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" -DskipTests test)
    local plugin_resolution="$AIENIE_CI_REPO_ROOT/$module/target/sca-plugin-resolution.txt"
    (cd "$AIENIE_CI_REPO_ROOT/$module" && mvn -B -ntp \
      -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" -Psecurity-audit \
      org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins \
      -DoutputFile="$plugin_resolution" -DoutputAbsoluteArtifactFilename=true)
    python3 "$AIENIE_CI_REPO_ROOT/scripts/ci/prepare-maven-plugin-sca.py" \
      "$AIENIE_CI_REPO_ROOT/$module/pom.xml" "$plugin_resolution"
    (cd "$AIENIE_CI_REPO_ROOT/$module" && mvn -B -ntp \
      -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" -Psecurity-audit -DskipTests dependency-check:check)
    python3 "$AIENIE_CI_REPO_ROOT/scripts/ci/verify-maven-plugin-sca.py" \
      "$AIENIE_CI_REPO_ROOT/$module/pom.xml" "$AIENIE_CI_REPO_ROOT/$module/target/dependency-check-report.json"
  done
}

aienie_ci_resolve_pnpm() {
  local module
  mkdir -p -- "$AIENIE_CI_CACHE_DIR/pnpm"
  for module in "${AIENIE_CI_PNPM_MODULES[@]}"; do
    pnpm --dir "$AIENIE_CI_REPO_ROOT/$module" fetch --frozen-lockfile \
      --store-dir "$AIENIE_CI_CACHE_DIR/pnpm"
    pnpm --dir "$AIENIE_CI_REPO_ROOT/$module" audit --audit-level=high
  done
}

aienie_ci_write_manifest() {
  local specs="$1" toolchains="$2" cache_count="$3" cache_sha="$4"
  mkdir -p -- "$(dirname "$AIENIE_DEPENDENCY_MANIFEST")"
  python3 - "$AIENIE_CI_REPO_ROOT" "$specs" "$toolchains" "$AIENIE_DEPENDENCY_MANIFEST" \
    "$AIENIE_CI_SOURCE_COMMIT" "$AIENIE_CI_TARGET_ARCHITECTURE" "$cache_count" "$cache_sha" \
    <<'PY'
import hashlib, json, pathlib, re, sys
root, specs_path, tools_path, out_path = map(pathlib.Path, sys.argv[1:5])
source_commit, target_arch, cache_count, cache_sha = sys.argv[5:9]
modules=[]; input_paths=set()
for line in specs_path.read_text(encoding='utf-8').splitlines():
    kind, relative = line.split('\t', 1)
    modules.append({'kind':kind, 'path':relative})
    input_paths.add(f'{relative}/package.json' if kind != 'maven' else f'{relative}/pom.xml')
    if kind == 'pnpm': input_paths.update((f'{relative}/pnpm-lock.yaml', f'{relative}/pnpm-workspace.yaml'))
inputs=[]
for relative in sorted(input_paths):
    path=root/relative
    inputs.append({'path':relative,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
toolchains={}
for line in tools_path.read_text(encoding='utf-8').splitlines():
    key,value=line.split('\t',1); toolchains[key]=re.sub(r'[\x00-\x1f\x7f]+',' ',value).strip()
value={
  'schema_version':'aienie-repository-dependency-manifest-v1',
  'source_commit':source_commit.lower(),
  'target_architecture':target_arch,
  'inputs':inputs,
  'modules':modules,
  'toolchains':toolchains,
  'cache':{'file_count':int(cache_count),'inventory_sha256':cache_sha},
  'resolve_checks':{
    'npm_audit':'not-applicable',
    'pnpm_audit':'passed' if any(m['kind']=='pnpm' for m in modules) else 'not-applicable',
    'maven_sca':'passed' if any(m['kind']=='maven' for m in modules) else 'not-applicable'
  }
}
out_path.write_text(json.dumps(value,sort_keys=True,separators=(',',':'))+'\n',encoding='utf-8')
PY
}

aienie_ci_require_resolved_security_checks() {
  python3 - "$AIENIE_DEPENDENCY_MANIFEST" <<'PYSECURITY'
import json,sys
value=json.load(open(sys.argv[1],encoding='utf-8'))
for kind in ('maven_sca','pnpm_audit'):
    if value.get('resolve_checks',{}).get(kind)!='passed':
        raise SystemExit('dependency security check missing: '+kind)
PYSECURITY
}

aienie_ci_has_script() {
  node -e 'const p=require(process.argv[1]); process.exit(p.scripts && p.scripts[process.argv[2]] ? 0 : 1)' "$1/package.json" "$2"
}

aienie_ci_build_maven() {
  local module
  for module in "${AIENIE_CI_MAVEN_MODULES[@]}"; do
    (cd "$AIENIE_CI_REPO_ROOT/$module" && mvn -B -ntp -o \
      -Dmaven.repo.local="$AIENIE_CI_CACHE_DIR/maven" clean package)
  done
}

aienie_ci_build_pnpm() {
  local module module_dir
  for module in "${AIENIE_CI_PNPM_MODULES[@]}"; do
    module_dir="$AIENIE_CI_REPO_ROOT/$module"
    pnpm --dir "$module_dir" install --offline --frozen-lockfile --store-dir "$AIENIE_CI_CACHE_DIR/pnpm"
    if aienie_ci_has_script "$module_dir" lint; then pnpm --dir "$module_dir" run lint; fi
    if aienie_ci_has_script "$module_dir" typecheck; then pnpm --dir "$module_dir" run typecheck; fi
    if aienie_ci_has_script "$module_dir" test:typecheck; then pnpm --dir "$module_dir" run test:typecheck; fi
    if aienie_ci_has_script "$module_dir" test:unit; then
      pnpm --dir "$module_dir" run test:unit
    elif aienie_ci_has_script "$module_dir" test; then
      pnpm --dir "$module_dir" test
    fi
    if aienie_ci_has_script "$module_dir" build; then pnpm --dir "$module_dir" run build; fi
  done
}

aienie_ci_prepare_phase() {
  AIENIE_CI_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[1]}")/../.." && pwd -P)"
  export AIENIE_CI_REPO_ROOT
  aienie_ci_require_value AIENIE_CI_PHASE
  aienie_ci_require_value AIENIE_CI_CACHE_DIR
  aienie_ci_require_value AIENIE_CI_OUTPUT_DIR
  aienie_ci_require_value AIENIE_DEPENDENCY_MANIFEST
  aienie_ci_require_value AIENIE_CI_SOURCE_COMMIT
  aienie_ci_require_value AIENIE_CI_TARGET_ARCHITECTURE
  [[ "$AIENIE_CI_SOURCE_COMMIT" =~ ^[0-9a-fA-F]{40}$|^[0-9a-fA-F]{64}$ ]] || aienie_ci_fail 'AIENIE_CI_SOURCE_COMMIT must be an immutable hexadecimal commit'
  (( $# == 1 )) || aienie_ci_fail 'the phase output must be the only positional argument'
  local positional_output cache_dir output_dir specs toolchains cache_count cache_sha
  positional_output="$(realpath -m -- "$1")"
  output_dir="$(aienie_ci_assert_safe_directory "$AIENIE_CI_OUTPUT_DIR" 'output')"
  cache_dir="$(aienie_ci_assert_safe_directory "$AIENIE_CI_CACHE_DIR" 'cache')"
  [[ "$positional_output" == "$output_dir" ]] || aienie_ci_fail 'positional output does not match AIENIE_CI_OUTPUT_DIR'
  [[ "$cache_dir" != "$output_dir" ]] || aienie_ci_fail 'cache and phase output must be separate directories'
  case "$cache_dir/" in "$output_dir/"*) aienie_ci_fail 'cache must not be nested under phase output' ;; esac
  case "$output_dir/" in "$cache_dir/"*) aienie_ci_fail 'phase output must not be nested under cache' ;; esac
  AIENIE_CI_CACHE_DIR="$cache_dir"; AIENIE_CI_OUTPUT_DIR="$output_dir"
  AIENIE_DEPENDENCY_MANIFEST="$(realpath -m -- "$AIENIE_DEPENDENCY_MANIFEST")"
  export AIENIE_CI_CACHE_DIR AIENIE_CI_OUTPUT_DIR AIENIE_DEPENDENCY_MANIFEST
  aienie_ci_assert_manifest_path
  aienie_ci_need python3; aienie_ci_need realpath; aienie_ci_need find
  aienie_ci_need cp; aienie_ci_need chmod; aienie_ci_need grep
  (( ${#AIENIE_CI_MAVEN_MODULES[@]} == 0 )) || { aienie_ci_need mvn; aienie_ci_need java; }
  (( ${#AIENIE_CI_PNPM_MODULES[@]} == 0 )) || { aienie_ci_need node; aienie_ci_need pnpm; }
  AIENIE_CI_SCRATCH_DIR="$(mktemp -d)"
  specs="$AIENIE_CI_SCRATCH_DIR/modules.tsv"; toolchains="$AIENIE_CI_SCRATCH_DIR/toolchains.tsv"
  trap aienie_ci_cleanup EXIT
  aienie_ci_write_specs "$specs"; aienie_ci_toolchains "$toolchains"
  case "$AIENIE_CI_PHASE" in
    resolve)
      [[ "${AIENIE_CI_NETWORK_MODE:-online}" != "offline" ]] || aienie_ci_fail 'resolve phase requires approved repository network access'
      if [[ -e "$AIENIE_CI_CACHE_DIR" ]]; then
        [[ -d "$AIENIE_CI_CACHE_DIR" && ! -L "$AIENIE_CI_CACHE_DIR" && -z "$(find "$AIENIE_CI_CACHE_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]] || aienie_ci_fail 'resolve cache must be an empty regular directory'
      else
        mkdir -p -- "$AIENIE_CI_CACHE_DIR"
      fi
      aienie_ci_assert_empty_output "$AIENIE_CI_OUTPUT_DIR"
      [[ ! -e "$AIENIE_DEPENDENCY_MANIFEST" ]] || aienie_ci_fail 'resolve dependency manifest output already exists'
      aienie_ci_resolve_maven; aienie_ci_resolve_pnpm
      aienie_ci_assert_no_special_entries "$AIENIE_CI_CACHE_DIR" 'resolved dependency cache'
      read -r cache_count cache_sha < <(aienie_ci_cache_inventory "$AIENIE_CI_CACHE_DIR")
      aienie_ci_write_manifest "$specs" "$toolchains" "$cache_count" "$cache_sha"
      printf 'Resolved repository dependencies: manifest=%s cacheFiles=%s\n' "$AIENIE_DEPENDENCY_MANIFEST" "$cache_count"
      return 0
      ;;
    build)
      [[ "${AIENIE_CI_NETWORK_MODE:-}" == "offline" ]] || aienie_ci_fail 'build requires AIENIE_CI_NETWORK_MODE=offline'
      [[ -d "$AIENIE_CI_CACHE_DIR" && ! -L "$AIENIE_CI_CACHE_DIR" ]] || aienie_ci_fail 'build dependency cache is missing or unsafe'
      aienie_ci_assert_no_special_entries "$AIENIE_CI_CACHE_DIR" 'dependency cache'
      aienie_ci_assert_build_output
      aienie_ci_require_resolved_security_checks
      AIENIE_CI_INPUT_CACHE_DIR="$AIENIE_CI_CACHE_DIR"
      aienie_ci_prepare_working_cache
      aienie_ci_build_maven; aienie_ci_build_pnpm
      export npm_config_offline=true npm_config_audit=false npm_config_fund=false
      export npm_config_cache="$AIENIE_CI_CACHE_DIR/npm" npm_config_store_dir="$AIENIE_CI_CACHE_DIR/pnpm"
      export MAVEN_ARGS="-o -Dmaven.repo.local=$AIENIE_CI_CACHE_DIR/maven ${MAVEN_ARGS:-}"
      export MAVEN_OPTS="-Dmaven.repo.local=$AIENIE_CI_CACHE_DIR/maven ${MAVEN_OPTS:-}"
      aienie_ci_detach_manifest
      ;;
    *) aienie_ci_fail 'AIENIE_CI_PHASE must be resolve or build' ;;
  esac
}
