"""Fail-closed local shared-data target resolution. No connections or mutations."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def environment(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)", line)
        if not match or match[1] in values:
            raise ValueError("INVALID_ENVIRONMENT")
        value = match[2]
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[match[1]] = value
    return values


def matrix(path):
    import yaml  # Already installed locally; never install dependencies implicitly.
    return yaml.safe_load(Path(path).read_text(encoding="utf-8-sig"))


def component(catalog, name):
    matches = [c for c in catalog["components"] if c["canonical_component_id"] == name]
    if len(matches) != 1:
        raise ValueError("AMBIGUOUS_COMPONENT")
    return matches[0]


def resolve(catalog, values, matrix_hash):
    result = dict(status="UNKNOWN", reason="TARGET_NOT_CONFIRMED", matrixSha256=matrix_hash,
                  environment="local", componentId="shared-mysql", database="aisocialgame")
    db = component(catalog, "shared-mysql")
    local = db["endpoints"]["local"]
    listener = [x for x in local.get("listeners", [])
                if x.get("name") == "mysql" and x.get("protocol") == "mysql"]
    if len(listener) != 1 or not local.get("host_ref") or listener[0].get("bind_scope") != "lan-private":
        return result
    host, port = local["canonical_domain"], listener[0]["port"]
    if not re.fullmatch(r"[a-z0-9.-]+", host or "") or type(port) is not int or not (1 <= port <= 65535):
        return result
    result["matrixTarget"] = dict(host=host, port=port, hostRef=local["host_ref"])
    if any(values.get(k, "local") != "local" for k in ("APP_ENV", "SPRING_PROFILES_ACTIVE")):
        result["reason"] = "ENVIRONMENT_IDENTITY_CONFLICT"
        return result
    raw = values.get("SPRING_DATASOURCE_URL", "")
    if not raw.startswith("jdbc:mysql://"):
        result["reason"] = "EXPLICIT_RUNTIME_URL_REQUIRED"
        return result
    uri = urlsplit(raw[5:])
    if uri.username or uri.password or uri.fragment or not re.fullmatch(r"[a-z0-9.-]+", uri.hostname or ""):
        result["reason"] = "INVALID_RUNTIME_URL"
        return result
    result["configuredTarget"] = dict(host=uri.hostname, port=uri.port, database=uri.path[1:])
    if (uri.hostname, uri.port, uri.path) != (host, port, "/aisocialgame"):
        result["reason"] = "MATRIX_RUNTIME_TARGET_CONFLICT"
        return result
    if values.get("ENV") != "local" or values.get("AIENIE_RUNTIME_PLANE") != "windows-local":
        result["reason"] = "ENVIRONMENT_IDENTITY_MISSING_OR_CONFLICTING"
        return result
    # Do not interpret arbitrary JDBC options, redirects, session SQL or alternate hosts.
    options = dict(parse_qsl(uri.query, strict_parsing=True))
    allowed = {"useSSL": "false", "allowPublicKeyRetrieval": "true", "serverTimezone": "UTC",
               "connectTimeout": "10000", "socketTimeout": "30000"}
    if any(k not in allowed or allowed[k] != v for k, v in options.items()):
        result["reason"] = "UNSUPPORTED_JDBC_OPTIONS"
        return result
    result.update(status="PASS", reason="MATRIX_AND_EXPLICIT_LOCAL_CONFIGURATION_MATCH",
                  jdbcUrl=f"jdbc:mysql://{host}:{port}/aisocialgame?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("matrix"); parser.add_argument("environment")
    args = parser.parse_args()
    try:
        print(json.dumps(resolve(matrix(args.matrix), environment(args.environment), sha(args.matrix))))
    except Exception:
        print(json.dumps(dict(status="UNKNOWN", reason="TARGET_INPUT_UNREADABLE_OR_INVALID")))
