#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import stat
import sys


from production_migration_manifest import MANIFEST, artifact_paths, load_manifest


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        raise SystemExit("usage: write-production-migration-artifacts.py BUNDLE OUTPUT")
    root = pathlib.Path(argv[1]).resolve(strict=True)
    output = pathlib.Path(argv[2])
    if output.exists() or output.is_symlink():
        raise SystemExit("migration artifact manifest output already exists")
    artifacts = []
    for relative in sorted(artifact_paths(load_manifest(root / MANIFEST))):
        path = root / relative
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode) or path.resolve(strict=True) != path:
            raise SystemExit(f"unsafe migration artifact: {relative}")
        artifacts.append({
            "mode": f"{stat.S_IMODE(metadata.st_mode):04o}",
            "path": relative,
            "sha256": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest(),
            "size": metadata.st_size,
        })
    value = {
        "artifacts": artifacts,
        "canonical_component_id": "ai-social-game",
        "schema_version": "aienie-production-migration-artifacts-v1",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")), encoding="utf-8", newline="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
