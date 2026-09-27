#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import shutil
import sys

from production_migration_manifest import load_manifest


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def digest(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        raise SystemExit("usage: write-production-sql-ledger.py REPOSITORY OUTPUT_DIRECTORY")
    repository = pathlib.Path(argv[1]).resolve(strict=True)
    output = pathlib.Path(argv[2])
    sql_output = output / "sql"
    if output.exists() or output.is_symlink():
        raise SystemExit("migration output already exists")
    sql_output.mkdir(parents=True)
    manifest = load_manifest(repository / "backend/sql/migrations.json")
    (output / "migrations.json").write_bytes(canonical(manifest))
    entries = []
    for entry in manifest["entries"]:
        ordinal, kind, name = entry["ordinal"], entry["kind"], entry["file"]
        source = repository / "backend/sql" / entry.get("source", name)
        if not source.is_file() or source.is_symlink() or source.resolve(strict=True).parent != repository / "backend/sql":
            raise SystemExit(f"unsafe SQL migration: {name}")
        raw = source.read_bytes()
        target = sql_output / name
        shutil.copyfile(source, target)
        if target.read_bytes() != raw:
            raise SystemExit(f"SQL migration changed during assembly: {name}")
        entries.append({
            "kind": kind,
            "ordinal": ordinal,
            "path": f"release/migrations/sql/{name}",
            "sha256": digest(raw),
        })
    ledger = {
        "authorization": {
            "minimum_release_manifest_version": 4,
            "outer_signature_required": True,
            "restore_point_required_before_execute": True,
        },
        "canonical_component_id": "ai-social-game",
        "entries": entries,
        "execution_plans": manifest["execution_plans"],
        "plan_selection": "sealed-candidate-file-no-auto-detection",
        "schema_version": "aienie-production-sql-ledger-v2",
    }
    ledger_raw = canonical(ledger)
    (output / "sql-ledger.json").write_bytes(ledger_raw)
    plan = {
        "authorization": "signed-v4-outer-manifest-and-target-helper",
        "canonical_component_id": "ai-social-game",
        "ledger_sha256": digest(ledger_raw),
        "schema_version": "aienie-production-sql-plan-v1",
        "selected_execution_plan": "fresh-empty-schema",
        "selected_ordinals": manifest["execution_plans"][1]["ordinals"],
    }
    (output / "production-plan.json").write_bytes(canonical(plan))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
