"""Read non-secret launcher parameters from a project's effective local YAML."""
from pathlib import Path
import argparse
import json
import re
import yaml
from config_pair import ConfigError, get_property, placeholders, sensitive


def merge(base, overlay):
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            merge(base[key], value)
        else:
            base[key] = value
    return base


def read(root: Path, profile="local", application=None):
    mapping = json.loads((root / "scripts/config-pair/mapping.json").read_text(encoding="utf-8"))
    resources = root / ("src/main/resources" if (root / "pom.xml").exists() else "backend/src/main/resources")
    source = resources / "application.yml"
    if not source.is_file() and not application:
        raise ConfigError("application.yml is unavailable")
    document = yaml.safe_load(source.read_text(encoding="utf-8")) or {} if source.is_file() else {}
    overlay = resources / ("application-" + profile + ".yml")
    if overlay.exists():
        merge(document, yaml.safe_load(overlay.read_text(encoding="utf-8")) or {})
    if application:
        merge(document, yaml.safe_load(Path(application).read_text(encoding="utf-8")) or {})
    def resolved(value, seen=()):
        if not isinstance(value, str):
            return str(value).lower() if isinstance(value, bool) else str(value)
        parts, cursor = [], 0
        for start, end, key, default in placeholders(value):
            parts.append(value[cursor:start])
            if sensitive(key) or key in seen:
                return None
            target = get_property(document, key)
            replacement = resolved(target, (*seen, key)) if target is not None else default
            if replacement is None:
                return None
            parts.append(replacement)
            cursor = end
        parts.append(value[cursor:])
        return "".join(parts)
    result = {}
    for key, paths in mapping["bindings"].items():
        if sensitive(key):
            continue
        for path in paths:
            value = get_property(document, path)
            if value is not None:
                value = resolved(value)
                if value is not None:
                    result[key] = value
                    break
    for key, value in document.get("runtime", {}).get("configuration", {}).items():
        if not sensitive(key):
            value = resolved(value)
            if value is not None:
                result[key] = value
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--profile", default="local")
    parser.add_argument("--application", type=Path)
    args = parser.parse_args()
    print(json.dumps(read(args.root.resolve(), args.profile, args.application)))
