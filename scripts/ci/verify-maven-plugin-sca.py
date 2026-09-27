#!/usr/bin/env python3
"""Require that the effective Maven plugin JAR set was included in the ODC report."""
from __future__ import annotations

import hashlib
import json
import pathlib
import sys


def sha256(path: pathlib.Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def main() -> None:
    if len(sys.argv) != 3:
        raise ValueError("usage: verify-maven-plugin-sca.py backend/pom.xml backend/target/dependency-check-report.json")
    pom = pathlib.Path(sys.argv[1]).resolve(strict=True)
    report = pathlib.Path(sys.argv[2]).resolve(strict=True)
    target = pom.parent / "target"
    if report.parent != target or not report.is_file():
        raise ValueError("SCA report is outside the expected Maven target")
    manifest = json.loads((target / "sca-effective-plugins.json").read_text(encoding="utf-8"))
    if manifest["pom_sha256"] != sha256(pom):
        raise ValueError("POM changed after effective plugin resolution")
    if manifest["suppression_sha256"] != sha256(pom.parent / "sca-suppressions.xml"):
        raise ValueError("SCA suppressions changed after plugin resolution")
    staged = manifest["staged_jars"]
    if not staged:
        raise ValueError("Effective plugin set is empty")
    stage = target / "sca-effective-plugins"
    for relative, expected in staged.items():
        path = (stage / relative).resolve(strict=True)
        if not path.is_relative_to(stage.resolve()) or sha256(path) != expected:
            raise ValueError(f"Staged Maven plugin artifact changed: {relative}")
    result = json.loads(report.read_text(encoding="utf-8"))
    observed: set[str] = set()
    def visit(value: dict) -> None:
        observed.add(value.get("sha256", "").lower())
        for related in value.get("relatedDependencies", []):
            visit(related)
    for dependency in result.get("dependencies", []):
        visit(dependency)
    absent = sorted(relative for relative, digest in staged.items() if digest not in observed)
    if absent:
        raise ValueError(f"ODC omitted {len(absent)} effective plugin artifacts: {absent[:5]}")
    print(f"ODC scanned all {len(staged)} override-aware plugin JARs")


if __name__ == "__main__":
    main()
