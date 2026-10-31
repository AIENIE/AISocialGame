"""Supply private credentials to the standalone local cleanup process without command-line secrets."""
import os
import subprocess
import sys
from shared_target import environment, matrix, resolve, sha


def main():
    mode, classpath, plan, env_file, jdbc, catalog, increment = sys.argv[1:]
    values = environment(env_file)
    target = resolve(matrix(catalog), values, sha(catalog))
    if mode not in ('dry-run', 'apply') or target.get('status') != 'PASS' or target.get('jdbcUrl') != jdbc:
        raise ValueError('Verified local target required')
    child = os.environ.copy()
    child.update(AISOCIAL_CLEANUP_ENV=values['ENV'], AISOCIAL_CLEANUP_PLANE=values['AIENIE_RUNTIME_PLANE'],
                 AISOCIAL_CLEANUP_DB_USERNAME=values['SPRING_DATASOURCE_USERNAME'],
                 AISOCIAL_CLEANUP_DB_PASSWORD=values['SPRING_DATASOURCE_PASSWORD'])
    return subprocess.run(['java.exe', '--class-path', classpath,
                           'com.aisocialgame.maintenance.LocalHistoryCleanup', mode, jdbc, plan, increment], env=child).returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('Cleanup configuration or verified local target unavailable.', file=sys.stderr)
        sys.exit(1)
