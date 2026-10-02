#!/usr/bin/env python3
import ipaddress
import json
import pathlib
import re
import sys
from urllib.parse import urlparse


contract_path, compose_path = map(pathlib.Path, sys.argv[1:3])
expected_component = sys.argv[3]
runtime_paths = list(map(pathlib.Path, sys.argv[4:]))
if len(runtime_paths) != 5:
    raise SystemExit("launcher, migration executor/entrypoint, SQL ledger, and production plan are required")
value = json.loads(contract_path.read_text(encoding="utf-8"))
if (
    value.get("schema_version"), value.get("canonical_component_id"),
    value.get("profile_id"), value.get("environment")
) != ("aienie-product-production-contract-v1", expected_component, "prod-products-68", "production"):
    raise SystemExit("production identity drifted")
if value.get("authorization") != {
    "minimum_release_manifest_version": 4,
    "policy": "signed-v4-only",
    "outer_signature_required": True,
}:
    raise SystemExit("signed v4 gate is mandatory")
origin = urlparse(value.get("public_origin", ""))
if origin.scheme != "https" or not origin.hostname or not origin.hostname.endswith(".seekerhut.com"):
    raise SystemExit("production origin is invalid")
if value.get("platform_injected_digest_fields") != [
    "source_commit", "config_digest", "artifact_digest", "image_digests"
]:
    raise SystemExit("platform digest closure drifted")
expected_overlay = {
    "schema_version": "aienie-production-protected-config-overlay-contract-v1",
    "allowed_files": [{"target_path": "application.yml", "file_mode": "0444", "owner": "runtime_identity", "consumers": ["backend-runtime", "production-migration-executor"]}, {
        "target_path": "env.txt",
        "file_mode": "0600",
        "owner": "runtime_identity",
        "consumers": ["backend-runtime", "production-migration-executor"],
    }],
    "forbidden_exact_paths": ["docker-compose.yml", "prompt.yml"],
    "forbidden_prefixes": ["backend/", "frontend/", "release/"],
    "artifact_override_policy": "deny",
    "unlisted_path_policy": "deny",
}
if value.get("protected_config_overlay_contract") != expected_overlay:
    raise SystemExit("protected production config overlay closure drifted")
expected_migration = {
    "mode": "explicit-sql-ledger",
    "ledger": "release/migrations/sql-ledger.json",
    "plan": "release/migrations/production-plan.json",
    "ledger_table": "aienie_sql_migration_ledger",
    "execution": "one-shot-before-app",
    "startup_behavior": "hibernate-validate-only",
    "checksum_drift": "fail-closed",
    "plan_selection": "sealed-candidate-file-no-auto-detection",
    "executor": {
        "path": "release/production-migration-executor",
        "cli": "precheck|execute|reconcile <component>",
        "stdin": "none",
        "environment": "fixed-protected-candidate-only",
        "stdout": "compact-canonical-secret-free-json",
    },
    "artifact_manifest": "release/production-migration-artifacts.json",
    "precheck": "strict-read-only",
    "restore_point": "consistent-jdbc-logical-checkpoint-before-execute",
    "authority": "signed-v4-platform-evidence-and-helper-invocation",
    "stdout_schema": "aienie-production-migration-executor-receipt-v1",
}
if value.get("migration") != expected_migration:
    raise SystemExit("AISocialGame one-shot SQL migration contract drifted")
authority_files = [
    "production-release-manifest-v4.json",
    "production-publication-receipt.json",
    "production-publication-receipt.json.sig",
    "production-publication-public-key.pem",
    "production-preactivation-authority.json",
    "production-preactivation-authority.json.sig",
    "production-preactivation-authority-public-key.pem",
]
expected_preactivation = {
    "schema_version": "aienie-production-preactivation-authority-v1",
    "required_for": ["backend-runtime"],
    "container_root": "/run/aienie/release-authority",
    "files": authority_files,
    "projection": "exact-seven-individual-read-only-files",
    "signature": "double-ed25519-canonical-json-raw64",
    "startup_policy": "fail-closed-before-production-b0",
}
if value.get("preactivation_authority") != expected_preactivation:
    raise SystemExit("AISocialGame signed preactivation authority contract drifted")
compose = compose_path.read_text(encoding="utf-8")
runtime = compose + "\n" + "\n".join(path.read_text(encoding="utf-8") for path in runtime_paths)
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1 if pathlib.Path(__file__).parent.name == "ci" and pathlib.Path(__file__).parent.parent.name == "fireflyChat" else 2]/"scripts/config-pair"))
from read_configuration import read
configuration_root = pathlib.Path(__file__).resolve().parents[1 if pathlib.Path(__file__).parent.parent.name == "fireflyChat" else 2]
configuration_values = read(configuration_root, "production")
runtime += "\n" + "\n".join(key+"="+value for key,value in configuration_values.items())

for forbidden in (
    ".testhut.top", ".aienie.com", ".localhut.com", "localhost", "extra_hosts",
    "host-gateway", "/etc/aienie", "env_file:", "build:", "container_name:"
):
    if forbidden in runtime:
        raise SystemExit(f"forbidden production authority leaked: {forbidden}")
for match in re.finditer(r"(?<![A-Za-z0-9])(?:\d{1,3}\.){3}\d{1,3}(?![A-Za-z0-9])", runtime):
    address = ipaddress.ip_address(match.group(0))
    if not (address.is_loopback or address.is_unspecified):
        raise SystemExit(f"non-listener IP literal leaked: {address}")
network_block = re.search(r"(?m)^networks:\s*\n((?:[ \t][^\n]*\n?)*)", compose)
if network_block and re.search(r"(?m)^\s+name:\s*", network_block.group(1)):
    raise SystemExit("fixed Compose network name is forbidden")
for listener in value.get("listeners", []):
    if listener not in compose:
        raise SystemExit(f"listener missing from Compose: {listener}")
for dependency in value.get("dependencies", []):
    host, _, port = dependency.get("authority", "").rpartition(":")
    if not dependency.get("tls_required") or not host.endswith(".seekerhut.com") or not port.isdigit():
        raise SystemExit(f"invalid TLS dependency: {dependency}")
    if host not in runtime or port not in runtime:
        raise SystemExit(f"dependency is not bound by reviewed runtime files: {dependency}")
for binding in value.get("persistent_bindings", []):
    source = binding.get("source", "")
    if not source.startswith(f"/srv/aienie-products/{expected_component}/") or source not in compose:
        raise SystemExit(f"persistence binding drifted: {binding}")
expected_backup = {
    "schema_version": "aienie-production-backup-contract-v1",
    "nightly": {
        "include": ["/srv/aienie-products/ai-social-game/records"],
        "exclude": [{
            "source": "/srv/aienie-products/ai-social-game/logs",
            "classification": "operational-log",
        }],
    },
}
if value.get("backup") != expected_backup:
    raise SystemExit("production backup classification drifted")
purposes = {item["source"]: item["purpose"] for item in value.get("persistent_bindings", [])}
classified = set(expected_backup["nightly"]["include"]) | {
    item["source"] for item in expected_backup["nightly"]["exclude"]
}
if classified != set(purposes) or purposes.get(
    "/srv/aienie-products/ai-social-game/logs"
) != "operational-log":
    raise SystemExit("production persistence closure is not fully classified")
if re.search(r"(?m)^\s*-\s+\./", compose) or "create_host_path: false" not in compose:
    raise SystemExit("production bind policy drifted")
for marker in (
    "source: ./backend/production-migration-entrypoint.sh",
    "target: /app/bin/production-migration-entrypoint.sh",
    "source: ./release/migrations",
    "target: /app/release/migrations",
):
    if marker not in compose:
        raise SystemExit("one-shot migration mount is missing")
for filename in authority_files:
    mount = (
        "      - {type: bind, source: ./.aienie-platform/" + filename
        + ", target: /run/aienie/release-authority/" + filename
        + ", read_only: true, bind: {create_host_path: false}}"
    )
    if compose.splitlines().count(mount) != 1:
        raise SystemExit("signed preactivation authority must use exact individual file mounts")
if "source: ./.aienie-platform, target: /run/aienie/release-authority" in compose:
    raise SystemExit("signed preactivation authority directory mounts are forbidden")
launcher = runtime_paths[0].read_text(encoding="utf-8")
if configuration_values.get("SPRING_JPA_HIBERNATE_DDL_AUTO") != "validate":
    raise SystemExit("long-running backend must only validate schema")
executor, entrypoint, ledger_path, plan_path = runtime_paths[1:]
if executor.name != "production-migration-executor" or entrypoint.name != "production-migration-entrypoint.sh":
    raise SystemExit("one-shot migration executable path drifted")
ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
plan = json.loads(plan_path.read_text(encoding="utf-8"))
from production_migration_manifest import load_manifest
source_manifest = load_manifest(ledger_path.parent / "migrations.json")
if ledger.get("schema_version") != "aienie-production-sql-ledger-v2" or len(ledger.get("entries", [])) != len(source_manifest["entries"]):
    raise SystemExit("SQL ledger identity drifted")
if plan.get("schema_version") != "aienie-production-sql-plan-v1" or not any(plan.get("selected_execution_plan") == candidate["id"] and plan.get("selected_ordinals") == candidate["ordinals"] for candidate in source_manifest["execution_plans"]):
    raise SystemExit("sealed SQL execution plan drifted")
