"""Write only Compose's referenced deployment parameters, never application secrets."""
from pathlib import Path
import argparse
import json
import os
import re
import yaml
from config_pair import ConfigError, get_property, parse_env, placeholders, sensitive


def parameters(root):
    compose = (root / 'docker-compose.yml').read_text(encoding='utf-8')
    requested = set(re.findall(r'(?<!\$)\$\{([A-Z][A-Z0-9_]*)', compose))
    available = {}
    marker = root / '.aienie-runtime-config-format'
    if marker.is_file() and marker.read_text(encoding='utf-8').strip() == 'application-yaml-env-v1':
        available['AIENIE_CONFIG_PAIR_ARTIFACT_V1'] = 'application-yaml-env-v1'
    grpc_marker = root / '.aienie-runtime-grpc-auth-format'
    if grpc_marker.is_file() and grpc_marker.read_text(encoding='utf-8').strip() == 'grpc-shared-key-v1':
        available['AIENIE_GRPC_SHARED_ARTIFACT_V1'] = 'grpc-shared-key-v1'
    for path in (root / 'application.yml', root / 'studio.application.yml'):
        if not path.exists():
            continue
        document = yaml.safe_load(path.read_text(encoding='utf-8')) or {}
        mapping_root = root / 'services/fireflychat-studio' if path.name == 'studio.application.yml' else root
        mapping_path = mapping_root / 'scripts/config-pair/mapping.json'
        mapping = json.loads(mapping_path.read_text(encoding='utf-8')) if mapping_path.exists() else {'bindings': {}}
        def resolve(value, seen=()):
            if not isinstance(value, str): return value
            pieces, cursor = [], 0
            for start, end, key, default in placeholders(value):
                if sensitive(key) or key in seen:
                    raise ConfigError('Deployment parameter references a secret or cycle: ' + key)
                target = get_property(document, key)
                replacement = resolve(target, (*seen, key)) if target is not None else default
                if replacement is None:
                    raise ConfigError('Missing deployment parameter reference: ' + key)
                pieces.extend((value[cursor:start], str(replacement)))
                cursor = end
            pieces.append(value[cursor:])
            return ''.join(pieces)
        for key, paths in mapping['bindings'].items():
            for prop in paths:
                value = get_property(document, prop)
                if value is not None:
                    if key in requested and not sensitive(key): available.setdefault(key, resolve(value))
                    break
        for key, value in document.get('runtime', {}).get('configuration', {}).items():
            if key in requested and not sensitive(key): available[key] = resolve(value)
    for name in ('images.env', '.aienie-production-secret-refs.env'):
        path = root / name
        if path.exists():
            available.update(parse_env(path.read_text(encoding='utf-8')))
    available.update({key: os.environ[key] for key in ('AIENIE_RUNTIME_UID', 'AIENIE_RUNTIME_GID') if key in os.environ})
    result = {}
    for key in requested:
        if sensitive(key):
            raise ConfigError('Compose must not interpolate an application secret: ' + key)
        value = available.get(key)
        if value is None:
            continue  # Compose applies its declared defaults/required-value rules.
        if isinstance(value, bool): value = str(value).lower()
        value = str(value)
        if list(placeholders(value)):
            raise ConfigError('Deployment parameter must be a literal YAML value: ' + key)
        if '\n' in value or '\r' in value:
            raise ConfigError('Multiline deployment parameter: ' + key)
        result[key] = value
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        values = parameters(args.root)
        args.output.write_text(''.join(key + "='" + value.replace("'", "\\'") + "'\n" for key, value in sorted(values.items())), encoding='utf-8')
        args.output.chmod(0o600)
    except (ConfigError, OSError, ValueError):
        raise SystemExit('Cannot prepare non-sensitive Compose parameters; no runtime instance was stopped.')
