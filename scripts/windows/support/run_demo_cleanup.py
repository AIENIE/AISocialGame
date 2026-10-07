"""Run maintenance with the existing private-secret/config-pair split, without logging secrets."""
import os
import subprocess
import sys

from shared_target import environment, matrix, resolve, sha


def main():
    mode, classpath, source, env_file, manifest, jdbc, catalog = sys.argv[1:]
    values = environment(env_file)
    target = resolve(matrix(catalog), values, sha(catalog))
    if mode not in ("dry-run", "apply") or target.get("status") != "PASS" or target.get("jdbcUrl") != jdbc:
        raise ValueError("Verified local target required")
    username = values.get("SPRING_DATASOURCE_USERNAME")
    password = values.get("SPRING_DATASOURCE_PASSWORD")
    if not username or not password:
        raise ValueError("Private database credentials unavailable")
    child = os.environ.copy()
    child.update(AISOCIAL_CLEANUP_ENV=values["ENV"], AISOCIAL_CLEANUP_PLANE=values["AIENIE_RUNTIME_PLANE"],
                 AISOCIAL_CLEANUP_DB_USERNAME=username, AISOCIAL_CLEANUP_DB_PASSWORD=password)
    return subprocess.run(["java.exe", "--class-path", classpath, source, mode, manifest, jdbc], env=child).returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        print("Cleanup configuration or verified target unavailable; no cleanup executed.", file=sys.stderr)
        sys.exit(1)
