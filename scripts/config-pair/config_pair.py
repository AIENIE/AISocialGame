"""Migrate Aienie configuration without evaluating or reporting secret values.

The JSON mappings shipped by each project map legacy variable names to Spring
property paths. YAML contains non-secret values and references to literal secrets.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import urllib.request

import yaml

FORMAT = "application-yaml-env-v1"
BLOCKED = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PATH",
           "LD_PRELOAD", "BASH_ENV", "SPRING_APPLICATION_JSON", "PYTHONPATH"}
OBSOLETE = {"ADMIN_CONFIG_FILE", "APP_ADMIN_CONFIG_FILE", "ADMIN_AUTH_CREDENTIAL_PATH",
            "ADMIN_AUTH_ENV_PATH", "ADMIN_ALLOW_NON_POSIX_LOCAL"}
SECRET = re.compile(r"(?:PASSWORD|SECRET|TOKEN|API_KEY|AES_KEY|ENCRYPTION_KEY|PRIVATE_KEY|SIGNING_KEY|TOTP_KEY|KEYRING|CREDENTIALS|WEBHOOK_URL|AUTHORIZATION|AUTH_HEADER|_JWT$|_ACCESS_KEY$)")
NONSECRET = re.compile(r"(?:_TOKEN_(?:BUDGET|LIMIT|COUNT|TTL|SECONDS|MS|STRICT|URL|ENABLED|EXPIRE|EXPIRATION|ISSUER|AUDIENCE|SOURCE)|_TOKENS$|_KEY_(?:PREFIX|VERSION)|_KEY_VERSION$|_API_KEY_(?:ENCRYPTION_REPAIR|LENGTH)|_PASSWORD_(?:LOGIN|ENABLED|MIN|MAX|LENGTH|MODE)|_ALLOW_CREDENTIALS$|_SECRET_FILE$|_TLS_PRIVATE_KEY$|_PRIVATE_KEY_(?:HOST|PATH|FILE)$|_CREDENTIALS_(?:PATH|FILE)$)")


class ConfigError(ValueError):
    """Messages must contain key names or paths, never configuration values."""


def sensitive(key: str) -> bool:
    return key == "GRPC_AUTH_CALLERS" or bool(SECRET.search(key) and not NONSECRET.search(key))


def parse_env(text: str) -> dict[str, str]:
    text = text.lstrip("\ufeff")
    values = {}
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)", line)
        if not match:
            raise ConfigError(f"invalid env entry at line {number}")
        key, value = match.groups()
        if key in values:
            raise ConfigError(f"duplicate env key: {key}")
        if key in BLOCKED or key.startswith("SPRING_CONFIG_"):
            raise ConfigError(f"blocked process key: {key}")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def encode_env(values: dict[str, str]) -> str:
    lines = ["# Sensitive configuration only. Values are literal; no shell expansion."]
    for key, value in sorted(values.items()):
        if "\n" in value or "\r" in value:
            raise ConfigError(f"multiline env value is unsupported: {key}")
        # Delimiters prevent a literal password beginning and ending in quotes
        # from being mistaken for env-file quoting by the runtime loader.
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = ("'" if value[0] == '"' else '"') + value + ("'" if value[0] == '"' else '"')
        lines.append(key + "=" + value)
    return "\n".join(lines) + "\n"


def placeholders(value: str):
    """Yield top-level Spring placeholders, including nested defaults."""
    i = 0
    while i < len(value):
        start = value.find("${", i)
        if start < 0:
            return
        end, depth = start + 2, 1
        while end < len(value) and depth:
            if value.startswith("${", end):
                depth += 1
                end += 2
            elif value[end] == "{":
                depth += 1
                end += 1
            elif value[end] == "}":
                depth -= 1
                end += 1
            else:
                end += 1
        if depth:
            raise ConfigError("unterminated configuration placeholder")
        inner = value[start + 2:end - 1]
        key, sep, default = inner.partition(":")
        yield start, end, key, default if sep else None
        i = end


def resolve_template(value: str, values: dict[str, str], keep_secrets=True) -> str:
    parts, cursor = [], 0
    for start, end, key, default in placeholders(value):
        parts.append(value[cursor:start])
        if sensitive(key) and keep_secrets:
            parts.append("${" + key + (":" if default == "" else "") + "}")
        elif key in values:
            parts.append(values[key])
        elif default is not None:
            parts.append(resolve_template(default, values, keep_secrets))
        else:
            parts.append("")
        cursor = end
    parts.append(value[cursor:])
    return "".join(parts)


def walk_nodes(node, path=""):
    if isinstance(node, yaml.MappingNode):
        seen = set()
        for key, child in node.value:
            if key.value in seen:
                raise ConfigError(f"duplicate YAML property: {path}.{key.value}")
            seen.add(key.value)
            yield from walk_nodes(child, f"{path}.{key.value}".lstrip("."))
    elif isinstance(node, yaml.SequenceNode):
        for index, child in enumerate(node.value):
            yield from walk_nodes(child, f"{path}[{index}]")
    else:
        yield path, node


def scalar(value, original: str):
    if isinstance(value, str) and "${" not in value:
        # Only canonical booleans/numbers are coerced. Selectors, usernames,
        # identifiers and leading-zero strings must remain exact strings.
        if value in ("true", "false"):
            return value == "true"
        if re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", value):
            if any(default and re.fullmatch(r"-?[0-9]+(?:\.[0-9]+)?", default)
                   for _, _, _, default in placeholders(original)):
                return float(value) if "." in value else int(value)
    return value


def dump_scalar(value):
    text = yaml.safe_dump(value, allow_unicode=True, width=100000, default_flow_style=True).strip()
    return text.removesuffix("\n...")


def set_property(document, path, value):
    tokens = re.findall(r"[^.\[\]]+|\[([0-9]+)\]", path)
    # re.findall with a capture omits ordinary tokens; tokenize explicitly.
    tokens = [int(m.group(1)) if m.group(1) is not None else m.group(0)
              for m in re.finditer(r"\[([0-9]+)\]|[^.\[\]]+", path)]
    current = document
    for i, token in enumerate(tokens):
        last = i == len(tokens) - 1
        if isinstance(token, int):
            while len(current) <= token:
                current.append(None)
            if last:
                current[token] = value
            elif current[token] is None:
                current[token] = [] if isinstance(tokens[i + 1], int) else {}
        else:
            if last:
                current[token] = value
            elif token not in current or current[token] is None:
                current[token] = [] if isinstance(tokens[i + 1], int) else {}
        if not last:
            current = current[token]


def get_property(document, path):
    current = document
    for m in re.finditer(r"\[([0-9]+)\]|[^.\[\]]+", path):
        token = int(m.group(1)) if m.group(1) is not None else m.group(0)
        try:
            current = current[token]
        except (KeyError, IndexError, TypeError):
            return None
    return current


def render(template: str, values: dict[str, str], mapping: dict):
    # The project template is already split. Replace properties using the
    # captured original binding, not a name-based guess over YAML keys.
    document = yaml.safe_load(template) or {}
    list(walk_nodes(yaml.compose(template)))
    for key, paths in mapping["bindings"].items():
        if key in OBSOLETE or key not in values:
            continue
        for path in paths:
            if sensitive(key):
                previous = get_property(document, path)
                optional = isinstance(previous, str) and bool(re.fullmatch(r"\$\{[A-Z][A-Z0-9_]*:\}", previous))
                set_property(document, path, "${" + key + (":}" if optional else "}"))
            else:
                previous = get_property(document, path)
                candidate = values[key]
                if isinstance(previous, bool) and candidate in ("true", "false"):
                    candidate = candidate == "true"
                elif isinstance(previous, list):
                    parsed = yaml.safe_load(candidate)
                    candidate = parsed if isinstance(parsed, list) else [item.strip() for item in candidate.split(",")]
                elif isinstance(previous, (int, float)) and not isinstance(previous, bool):
                    if not re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", candidate):
                        raise ConfigError(f"invalid numeric configuration: {key}")
                    candidate = type(previous)(candidate)
                set_property(document, path, candidate)
    secrets = {key: value for key, value in values.items() if sensitive(key) and key not in OBSOLETE}
    # Split legacy AI caller identities and their HMAC keys. The existing
    # gateway accepts a comma-separated caller:secret representation.
    if values.get("GRPC_AUTH_CALLERS"):
        entries = []
        for entry in values["GRPC_AUTH_CALLERS"].split(","):
            caller, separator, secret = entry.partition(":")
            if not separator or not caller or not secret:
                raise ConfigError("invalid GRPC_AUTH_CALLERS entry")
            key = "GRPC_CALLER_" + hashlib.sha256(caller.encode()).hexdigest()[:16].upper() + "_SECRET"
            secrets[key] = secret
            entries.append(caller + ":${" + key + "}")
        for path in mapping["bindings"].get("GRPC_AUTH_CALLERS", []):
            set_property(document, path, ",".join(entries))
        secrets.pop("GRPC_AUTH_CALLERS", None)
    if values.get("ADMIN_TOTP_ENCRYPTION_KEYS"):
        entries = []
        for entry in values["ADMIN_TOTP_ENCRYPTION_KEYS"].split(","):
            version, separator, secret = entry.partition(":")
            if not separator or not re.fullmatch(r"[A-Za-z0-9_-]+", version) or not secret:
                raise ConfigError("invalid ADMIN_TOTP_ENCRYPTION_KEYS entry")
            key = "ADMIN_TOTP_ENCRYPTION_KEY_" + version.upper().replace("-", "_")
            if key in secrets and secrets[key] != secret:
                raise ConfigError("conflicting TOTP key version: " + key)
            secrets[key] = secret
            entries.append(version + ":${" + key + "}")
        for path in mapping["bindings"].get("ADMIN_TOTP_ENCRYPTION_KEYS", ["runtime.configuration.ADMIN_TOTP_ENCRYPTION_KEYS"]):
            set_property(document, path, ",".join(entries))
        secrets.pop("ADMIN_TOTP_ENCRYPTION_KEYS", None)
    if values.get("APP_AI_SOURCE_KEYRING"):
        entries = []
        for entry in re.split("[;,]", values["APP_AI_SOURCE_KEYRING"]):
            identity, separator, secret = entry.strip().partition("=")
            identity, secret = identity.strip(), secret.strip()
            if not separator or not re.fullmatch(r"[A-Za-z0-9_-]+", identity) or not secret:
                raise ConfigError("invalid APP_AI_SOURCE_KEYRING entry")
            key = "AI_SOURCE_KEY_" + hashlib.sha256(identity.encode()).hexdigest()[:16].upper() + "_SECRET"
            if key in secrets and secrets[key] != secret:
                raise ConfigError("conflicting AI source key identity")
            secrets[key] = secret
            entries.append(identity + "=${" + key + "}")
        for path in mapping["bindings"].get("APP_AI_SOURCE_KEYRING", ["runtime.configuration.APP_AI_SOURCE_KEYRING"]):
            set_property(document, path, ";".join(entries))
        secrets.pop("APP_AI_SOURCE_KEYRING", None)
    unknown = sorted(key for key in values if key not in mapping["bindings"]
                     and not sensitive(key) and key not in OBSOLETE)
    for key in unknown:
        set_property(document, "runtime.configuration." + key, values[key])
    text = yaml.safe_dump(document, sort_keys=False, allow_unicode=True, width=120)
    return text, encode_env(secrets)


def atomic_write(path: Path, content: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode if path.exists() else 0o600
    fd, temporary = tempfile.mkstemp(prefix=".config-pair-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if os.name == "nt" and path.exists():
            # Writing the existing inode preserves its explicit NTFS ACL.
            with path.open("wb") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
        else:
            os.chmod(temporary, mode & 0o777)
            os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def migrate_files(env_path: Path, yaml_path: Path, mapping_path: Path,
                  template_path: Path, backup: Path, admin_paths=(), fault=None, credential_source="reject"):
    paths = [env_path, yaml_path, *admin_paths]
    if any(path.is_symlink() for path in paths):
        raise ConfigError("configuration files must not be symlinks")
    original = {path: path.read_bytes() if path.exists() else None for path in paths}
    values = parse_env(env_path.read_text(encoding="utf-8-sig"))
    for path in admin_paths:
        if not path.exists():
            continue
        for key, value in parse_env(path.read_text(encoding="utf-8-sig")).items():
            if key in values and values[key] != value:
                if credential_source == "env":
                    continue
                if credential_source != "admin":
                    raise ConfigError(f"conflicting configuration key: {key}")
            values[key] = value
    mapping = json.loads(mapping_path.read_text(encoding="utf-8"))
    template = yaml_path.read_text(encoding="utf-8") if yaml_path.exists() else template_path.read_text(encoding="utf-8")
    yaml_text, env_text = render(template, values, mapping)
    candidate_env = parse_env(env_text)
    yaml.safe_load(yaml_text)
    for key, value in values.items():
        if sensitive(key) and key not in ("GRPC_AUTH_CALLERS", "ADMIN_TOTP_ENCRYPTION_KEYS", "APP_AI_SOURCE_KEYRING") and candidate_env.get(key) != value:
            raise ConfigError(f"secret round-trip failed: {key}")
    backup.mkdir(parents=True, exist_ok=True)
    for path, content in original.items():
        if content is not None:
            destination = backup / (hashlib.sha256(str(path.resolve()).encode()).hexdigest()[:16] + "-" + path.name)
            if not destination.exists():
                atomic_write(destination, content)
    try:
        atomic_write(yaml_path, yaml_text.encode("utf-8"))
        if fault:
            fault()
        atomic_write(env_path, env_text.encode("utf-8"))
        if yaml_path.read_bytes() != yaml_text.encode("utf-8") or parse_env(env_path.read_text(encoding="utf-8")) != candidate_env:
            raise ConfigError("configuration read-back failed")
        for path in admin_paths:
            if path.exists():
                path.unlink()
    except BaseException:
        for path, content in original.items():
            if content is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, content)
        raise
    return {"status": "migrated", "secret_keys": sorted(candidate_env),
            "nonsecret_keys": sorted(k for k in values if not sensitive(k) and k not in OBSOLETE)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, required=True)
    parser.add_argument("--application", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--backup", type=Path, required=True)
    parser.add_argument("--admin", type=Path, action="append", default=[])
    args = parser.parse_args()
    try:
        result = migrate_files(args.env, args.application, args.mapping, args.template, args.backup, args.admin)
        print(json.dumps(result))
    except (ConfigError, OSError) as error:
        # YAML parser diagnostics may include source lines. Never print those.
        print(type(error).__name__ + ": " + str(error) if isinstance(error, ConfigError) else "configuration I/O failed")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
