#!/usr/bin/env python3
"""Stage Maven's override-aware plugin resolution for the release SCA gate."""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from datetime import date

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PATH = re.compile(r"(?P<path>(?:[A-Za-z]:[\\/]|/).+)$")


def digest(path: pathlib.Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def validate_suppressions(path: pathlib.Path, resolved_sha1: set[str]) -> None:
    """Only expiring, exact-artifact/CVE false-positive rules may enter the SCA gate."""
    root = ET.parse(path).getroot()
    namespace = "https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd"
    if root.tag != f"{{{namespace}}}suppressions":
        raise ValueError("Unexpected SCA suppression schema")
    if not list(root):
        raise ValueError("SCA suppression list is empty")
    for rule in root:
        if rule.tag != f"{{{namespace}}}suppress":
            raise ValueError("Unexpected SCA suppression rule")
        until = rule.attrib.get("until", "")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}Z", until) or date.fromisoformat(until[:-1]) <= date.today():
            raise ValueError("SCA suppression has no future expiry")
        fields: dict[str, list[str]] = {}
        for child in rule:
            name = child.tag.removeprefix(f"{{{namespace}}}")
            if name not in {"notes", "sha1", "cve"} or child.attrib:
                raise ValueError("Broad SCA suppression selector is forbidden")
            fields.setdefault(name, []).append((child.text or "").strip())
        if len(fields.get("notes", [])) != 1 or "Owner:" not in fields["notes"][0]:
            raise ValueError("SCA exception needs a named owner")
        hashes = fields.get("sha1", [])
        if len(hashes) != 1 or not re.fullmatch(r"[a-fA-F0-9]{40}", hashes[0]) or hashes[0].lower() not in resolved_sha1:
            raise ValueError("SCA exception must match one resolved plugin artifact SHA-1")
        cves = fields.get("cve", [])
        if not cves or any(not re.fullmatch(r"CVE-\d{4}-\d{4,}", cve) for cve in cves):
            raise ValueError("SCA exception needs exact CVE identifiers")


def declared_plugins(pom: pathlib.Path) -> dict[str, str]:
    root = ET.parse(pom).getroot()
    properties = root.find("m:properties", NS)
    values = {entry.tag.rsplit("}", 1)[-1]: entry.text or "" for entry in properties} if properties is not None else {}
    plugins: dict[str, str] = {}
    for location in ("m:build/m:plugins/m:plugin", "m:profiles/m:profile/m:build/m:plugins/m:plugin"):
        for item in root.findall(location, NS):
            group = item.findtext("m:groupId", "org.apache.maven.plugins", NS)
            artifact = item.findtext("m:artifactId", namespaces=NS)
            version = item.findtext("m:version", namespaces=NS)
            if not group or not artifact or not version:
                raise ValueError("Every release plugin needs a pinned version")
            if version.startswith("${") and version.endswith("}"):
                version = values.get(version[2:-1], "")
            if not version or "${" in version:
                raise ValueError(f"Unresolved release plugin version: {group}:{artifact}")
            key = f"{group}:{artifact}"
            if key in plugins and plugins[key] != version:
                raise ValueError(f"Conflicting release plugin versions: {key}")
            plugins[key] = version
    if not plugins or "org.owasp:dependency-check-maven" not in plugins:
        raise ValueError("Security profile plugin is missing")
    return plugins


def parse_resolution(path: pathlib.Path) -> dict[str, list[dict[str, str]]]:
    blocks: dict[str, list[dict[str, str]]] = {}
    current = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("   ") and not raw.startswith("      "):
            coordinate = raw.strip().split(":", 4)
            if len(coordinate) < 4:
                raise ValueError("Malformed plugin identity")
            current = f"{coordinate[0]}:{coordinate[1]}:{coordinate[3]}"
            if current in blocks:
                raise ValueError(f"Duplicate plugin resolution: {current}")
            blocks[current] = []
        elif raw.startswith("      "):
            if current is None:
                raise ValueError("Plugin artifact without owner")
            match = PATH.search(raw.strip())
            if match is None:
                raise ValueError("Resolved plugin artifact has no absolute file path")
            artifact_path = pathlib.Path(match.group("path"))
            coordinate = raw.strip()[: match.start("path")].rstrip(":").split(":")
            if len(coordinate) not in (4, 5) or not artifact_path.is_absolute() or not artifact_path.is_file():
                raise ValueError("Resolved plugin artifact is unavailable")
            if artifact_path.is_symlink():
                raise ValueError("Plugin artifact symlink is forbidden")
            blocks[current].append({
                "gav": ":".join(coordinate), "path": str(artifact_path), "sha256": digest(artifact_path),
                "type": coordinate[2],
            })
    return blocks


def main() -> None:
    if len(sys.argv) != 3:
        raise ValueError("usage: prepare-maven-plugin-sca.py backend/pom.xml resolved-plugins.txt")
    pom = pathlib.Path(sys.argv[1]).resolve(strict=True)
    resolved = pathlib.Path(sys.argv[2]).resolve(strict=True)
    project = pom.parent
    target = project / "target"
    stage = target / "sca-effective-plugins"
    if stage.resolve().parent != target.resolve() or not resolved.is_relative_to(target.resolve()):
        raise ValueError("SCA paths must remain in the Maven target directory")
    expected = declared_plugins(pom)
    blocks = parse_resolution(resolved)
    selected: dict[str, list[dict[str, str]]] = {}
    for key, version in expected.items():
        identity = f"{key}:{version}"
        values = blocks.get(identity)
        if not values:
            raise ValueError(f"Declared plugin was not resolved: {identity}")
        selected[identity] = values
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    copied: dict[str, str] = {}
    for plugin, values in selected.items():
        for value in values:
            if value["type"] != "jar":
                continue
            coordinate = value["gav"].split(":")
            name = pathlib.Path(value["path"]).name
            destination = stage / coordinate[0] / coordinate[1] / coordinate[-1] / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            relative = destination.relative_to(stage).as_posix()
            if relative in copied and copied[relative] != value["sha256"]:
                raise ValueError(f"Plugin artifact collision: {relative}")
            if relative not in copied:
                shutil.copyfile(value["path"], destination)
                if digest(destination) != value["sha256"]:
                    raise ValueError(f"Staged plugin artifact changed: {relative}")
                copied[relative] = value["sha256"]
            value["staged"] = relative
    if not copied:
        raise ValueError("Plugin SCA stage is empty")
    suppression = project / "sca-suppressions.xml"
    resolved_sha1 = {hashlib.sha1(pathlib.Path(value["path"]).read_bytes()).hexdigest()
                     for values in selected.values() for value in values if value["type"] == "jar"}
    validate_suppressions(suppression, resolved_sha1)
    (target / "sca-effective-plugins.json").write_text(json.dumps({
        "resolver": "maven-dependency-plugin:3.11.0:resolve-plugins",
        "pom_sha256": digest(pom), "suppression_sha256": digest(suppression),
        "plugins": selected, "staged_jars": copied,
    }, indent=2, sort_keys=True), encoding="utf-8")
    print(f"Staged {len(copied)} plugin JARs from {len(selected)} pinned plugins")


if __name__ == "__main__":
    main()
