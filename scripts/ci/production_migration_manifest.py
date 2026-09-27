"""Shared parser for the versioned, signed migration source manifest."""
from __future__ import annotations

import json
import pathlib
import re

MANIFEST = "release/migrations/migrations.json"
MODULE = "release/production_migration_manifest.py"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate migration manifest field")
        result[key] = value
    return result


def load_manifest(path: pathlib.Path) -> dict:
    if not path.is_file() or path.is_symlink() or path.resolve(strict=True) != path.absolute():
        raise ValueError("unsafe migration manifest path")
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    if not isinstance(value, dict) or set(value) != {"schema_version", "entries", "execution_plans", "baseline_includes"}:
        raise ValueError("invalid migration manifest fields")
    if value["schema_version"] != "aisocialgame-migrations-v1":
        raise ValueError("unsupported migration manifest version")
    entries = value["entries"]
    if not isinstance(entries, list) or not entries:
        raise ValueError("empty migration manifest")
    files = set()
    for ordinal, entry in enumerate(entries, 1):
        if (not isinstance(entry, dict) or set(entry) not in ({"ordinal", "kind", "file"}, {"ordinal", "kind", "file", "source"})
                or type(entry["ordinal"]) is not int or entry["ordinal"] != ordinal
                or entry["kind"] != ("baseline" if ordinal == 1 else "upgrade")
                or not isinstance(entry["file"], str)
                or not re.fullmatch(r"[a-zA-Z0-9_-]+\.sql", entry["file"])
                or entry["file"] in files):
            raise ValueError("invalid migration manifest entry")
        if "source" in entry and (ordinal != 1 or not isinstance(entry["source"], str)
                or not re.fullmatch(r"[a-zA-Z0-9_-]+\.sql", entry["source"])):
            raise ValueError("invalid frozen baseline source")
        files.add(entry["file"])
    if entries[0]["file"] != "schema.sql":
        raise ValueError("baseline must be schema.sql")
    included = value["baseline_includes"]
    if (not isinstance(included, list) or any(type(i) is not int or i < 2 or i > len(entries) for i in included)
            or included != sorted(set(included))):
        raise ValueError("invalid baseline inclusion list")
    # Baseline already incorporates some old upgrades; never apply those twice.
    expected = [
        {"id": "existing-legacy-schema", "ordinals": list(range(2, len(entries) + 1))},
        {"id": "fresh-empty-schema", "ordinals": [i for i in range(1, len(entries) + 1) if i not in included]},
    ]
    if value["execution_plans"] != expected:
        raise ValueError("migration execution plans are incomplete or reordered")
    return value


def artifact_paths(manifest: dict) -> set[str]:
    return {
        "backend/app.jar", "backend/production-migration-entrypoint.sh",
        "release/migrations/production-plan.json", "release/migrations/sql-ledger.json",
        "release/production-migration-executor", MANIFEST, MODULE,
        *(f"release/migrations/sql/{entry['file']}" for entry in manifest["entries"]),
    }
