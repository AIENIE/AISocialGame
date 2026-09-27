"""Local build provenance. No network, credentials or application startup."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

SCHEMA = 2
SET_VERSION = "closure-v2"

def fingerprint(root):
    files = set()
    for directory in ("backend/src/main", "backend/src/test", "backend/sql", "frontend/src", "frontend/tests", "scripts/windows"):
        files.update(p for p in (root / directory).rglob("*") if p.is_file() and "__pycache__" not in p.parts
                     and p.suffix in (".java", ".json", ".txt", ".yml", ".yaml", ".properties", ".ts", ".tsx", ".py", ".ps1", ".sql"))
    files.update(root / name for name in ("backend/pom.xml", "frontend/package.json", "frontend/pnpm-lock.yaml", "frontend/pnpm-workspace.yaml", "doc/ai-realism-manual-review-rubric.md") if (root / name).exists())
    digest = hashlib.sha256()
    for path in sorted(files, key=lambda p: p.relative_to(root).as_posix()):
        digest.update(path.relative_to(root).as_posix().encode()); digest.update(b"\0"); digest.update(path.read_bytes())
    return digest.hexdigest()

def identity(root):
    source = fingerprint(root)
    return dict(sourceFingerprint=source, buildId=hashlib.sha256(("closure-build-v1:" + source).encode()).hexdigest())

def prepare(root):
    value = identity(root)
    target = root / "backend/target/generated-resources/closure/closure-build.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, sort_keys=True), encoding="utf-8")
    return value

def artifact(root, jar, verify=False):
    value = identity(root)
    with zipfile.ZipFile(jar) as bundle:
        embedded = json.loads(bundle.read("BOOT-INF/classes/closure-build.json"))
    if embedded != value:
        raise ValueError("STALE_BUILD_IDENTITY")
    value.update(artifactSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), artifactName=jar.name)
    manifest = root / "backend/target/closure-build-manifest.json"
    if verify:
        if not manifest.exists() or json.loads(manifest.read_text(encoding="utf-8")) != value: raise ValueError("ARTIFACT_MANIFEST_MISMATCH")
    else:
        manifest.write_text(json.dumps(value, indent=2), encoding="utf-8")
    return value

if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("mode", choices=("fingerprint", "prepare", "artifact", "verify")); p.add_argument("root"); p.add_argument("--jar")
    args = p.parse_args(); root = Path(args.root).resolve()
    value = prepare(root) if args.mode == "prepare" else artifact(root, Path(args.jar), args.mode == "verify") if args.mode in ("artifact","verify") else identity(root)
    print(json.dumps(value))
