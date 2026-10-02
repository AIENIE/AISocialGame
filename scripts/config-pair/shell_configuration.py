"""Effective YAML settings for server shell preflight; secrets are never exported wholesale."""
from pathlib import Path
import argparse
import json
import sys
from config_pair import ConfigError, get_property, parse_env, placeholders, sensitive, walk_nodes
from read_configuration import read
import yaml


def configuration(root, envfile):
    application = envfile.with_name('studio.application.yml' if envfile.name == 'studio.env.txt' else 'application.yml')
    companion=envfile.with_name(envfile.name+'.application.yml')
    if companion.is_file():application=companion
    if not application.is_file() or application.is_symlink():
        raise ConfigError('missing regular application.yml')
    values = parse_env(envfile.read_text(encoding='utf-8'))
    if 'GRPC_SHARED_SECRET' in values:
        from grpc_shared import validate_secret, obsolete
        validate_secret(values['GRPC_SHARED_SECRET'])
        if any(obsolete(key) for key in values):
            raise ConfigError('obsolete public gRPC credential remains')
    if any(not sensitive(key) for key in values):
        raise ConfigError('env.txt may contain only sensitive keys')
    values.update(read(root, '', application))
    mapping=json.loads((root/'scripts/config-pair/mapping.json').read_text(encoding='utf-8'))['bindings']
    if 'GRPC_SHARED_SECRET' in mapping:
        from grpc_shared import validate_secret, obsolete
        validate_secret(values.get('GRPC_SHARED_SECRET'))
        if any(obsolete(key) for key in values):raise ConfigError('obsolete public gRPC credential remains')
    doc=yaml.safe_load(application.read_text(encoding='utf-8'))
    def resolve(value, seen=()):
        if not isinstance(value,str):return value
        result,cursor=[],0
        for start,end,key,default in placeholders(value):
            if key in seen:raise ConfigError('cyclic configuration reference')
            replacement=values.get(key)
            if replacement is None:replacement=get_property(doc,key)
            if replacement is None:replacement=default
            if replacement is None:raise ConfigError('missing required secret: '+key)
            result.extend([value[cursor:start],str(resolve(replacement,(*seen,key)))])
            cursor=end
        return ''.join(result)+value[cursor:]
    for key,paths in mapping.items():
        if not sensitive(key):continue
        for path in paths:
            raw=get_property(doc,path)
            if raw is not None:
                values[key]=resolve(raw)
                break
    env, mode = values.get('ENV'), values.get('AUTH_MODE')
    if env not in ('local', 'test', 'production') or mode not in ('password', 'totp') or (env != 'local' and mode != 'totp'):
        raise ConfigError('invalid effective ENV/AUTH_MODE')
    for key in ('MYSQL_PASSWORD','DB_PASSWORD','APP_MYSQL_PASSWORD'):
        if key in mapping and not str(values.get(key,'')).strip():raise ConfigError('missing required database credential: '+key)
    username=next((key for key in ('APP_ADMIN_LOGIN_USERNAME','APP_ADMIN_USERNAME','ADMIN_USERNAME') if key in mapping),None)
    if username:
        if not str(values.get(username,'')).strip():raise ConfigError('missing administrator identity')
        prefix=username.removesuffix('USERNAME')
        if not str(values.get(prefix+'PASSWORD_HASH','')).strip() and not str(values.get(prefix+'PASSWORD','')).strip():raise ConfigError('missing administrator credential')
    if mode=='totp':
        key=('ADMIN_TOTP_KEY_'+str(values.get('ADMIN_TOTP_ACTIVE_KEY_VERSION','v1')).upper()) if 'ADMIN_TOTP_KEY_V1' in mapping else next((key for key in ('ADMIN_TOTP_ENCRYPTION_KEYS','APP_ADMIN_TOTP_ENCRYPTION_KEY','ADMIN_TOTP_SECRET') if key in mapping),None)
        if key and not str(values.get(key,'')).strip():raise ConfigError('missing required TOTP credential: '+key)
    for _, node in walk_nodes(yaml.compose(application.read_text(encoding='utf-8'))):
        if not isinstance(node.value, str): continue
        for _, _, key, default in placeholders(node.value):
            if sensitive(key) and default is None and not values.get(key):
                raise ConfigError('missing required secret: ' + key)
    return values


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('envfile', type=Path)
    parser.add_argument('--key')
    parser.add_argument('--validate', action='store_true')
    args = parser.parse_args()
    try:
        values = configuration(args.root, args.envfile)
        if args.key:
            sys.stdout.write(values.get(args.key, ''))
        elif not args.validate:
            for key, value in values.items():
                if sensitive(key): continue
                if not key.replace('_','').isalnum() or '\0' in value:
                    raise ConfigError('invalid shell configuration key')
                sys.stdout.buffer.write(key.encode() + b'\0' + value.encode() + b'\0')
    except (ConfigError, OSError, ValueError):
        raise SystemExit('Invalid application.yml/env.txt pair; no runtime instance was stopped.')
