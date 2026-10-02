"""Transform a configuration pair to the environment-wide public gRPC key."""
import base64
import json
import re
import secrets
from pathlib import Path
import yaml
from config_pair import ConfigError, encode_env, get_property, parse_env, set_property, placeholders, walk_nodes

KEY = 'GRPC_SHARED_SECRET'
CAPABILITY = 'grpc-shared-key-v1'
SECRET_KEYS = {
    'BILLING_ONBOARDING_SERVICE_JWT_SECRET', 'EXTERNAL_AI_HMAC_SECRET',
    'EXTERNAL_USER_SERVICE_JWT_SECRET', 'EXTERNAL_PAY_SERVICE_JWT_SECRET',
    'APP_EXTERNAL_USERSERVICE_JWT_SECRET', 'APP_EXTERNAL_PAYSERVICE_JWT_SECRET',
    'APP_EXTERNAL_AISERVICE_HMAC_SECRET', 'USERSERVICE_SERVICE_JWT_SECRET',
    'PAYSERVICE_JWT_SECRET', 'AISERVICE_HMAC_SECRET', 'USERSERVICE_JWT_SECRET',
    'APP_EXTERNAL_AI_HMAC_SECRET', 'AI_SERVICE_CALLER_SECRET',
    'APP_EXTERNAL_USER_JWT_SECRET', 'APP_EXTERNAL_PAY_JWT_SECRET', 'AI_HMAC_SECRET',
}
LEGACY_KEYS = {
    'GRPC_AUTH_CALLERS', 'GRPC_JWT_SECRET', 'GRPCUI_AUTHORIZATION_HEADER',
    'USERSERVICE_INTERNAL_GRPC_TOKEN', 'BILLING_ONBOARDING_LEGACY_STATIC_TOKEN',
    'PAY_SERVICE_JWT', 'EXTERNAL_USER_INTERNAL_TOKEN', 'EXTERNAL_PAY_TOKEN',
    'APP_EXTERNAL_USER_INTERNAL_TOKEN', 'APP_EXTERNAL_USER_TOKEN',
    'APP_EXTERNAL_PAY_TOKEN', 'APP_EXTERNAL_USERSERVICE_INTERNAL_TOKEN',
    'APP_EXTERNAL_PAYSERVICE_TOKEN', 'PAYSERVICE_TOKEN', 'PAYSERVICE_JWT',
    'EXTERNAL_PAY_SERVICE_JWT', 'APP_EXTERNAL_PAYSERVICE_JWT',
    'EXTERNAL_USER_INTERNAL_GRPC_TOKEN', 'APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN',
    'PAYSERVICE_SERVICE_JWT', 'APP_EXTERNAL_PAY_SERVICE_JWT',
    'SECURITY_GRPC_LEGACY_INTERNAL_TOKEN_ENABLED',
    'GRPC_INTERNAL_CLIENT_SERVICE_NAME', 'GRPC_INTERNAL_CLIENT_TOKEN_TTL_SECONDS',
}

def obsolete(key):
    return key in SECRET_KEYS | LEGACY_KEYS or bool(re.fullmatch(r'(GRPC_CALLER_[A-Z0-9_]+|SECURITY_GRPC_SERVICE_JWT_CALLERS_\d+)_SECRET', key))

def validate_secret(value):
    if not isinstance(value, str) or not 32 <= len(value.encode()) <= 4096 or len(set(value)) < 8:
        raise ConfigError('invalid environment gRPC shared secret')
    if any(c.isspace() or ord(c) < 32 for c in value) or any(x in value.lower() for x in ('${', 'replace', 'change-me', 'change_me', 'changeme', 'placeholder', 'example', 'fixture', 'dummy', 'password', 'sample', '<', '>')):
        raise ConfigError('invalid environment gRPC shared secret')

def environment_key(path):
    """Protected key store; never regenerate an existing environment's key."""
    from config_pair import atomic_write
    if path.is_file():
        value=parse_env(path.read_text(encoding='utf-8'))[KEY]
    else:
        value=base64.b64encode(secrets.token_bytes(32)).decode()
        atomic_write(path, encode_env({KEY:value}).encode())
    validate_secret(value)
    return value

def remove_property(doc, path):
    parts=[int(m.group(1)) if m.group(1) is not None else m.group(0) for m in re.finditer(r'\[([0-9]+)\]|[^.\[\]]+',path)];current=doc
    for part in parts[:-1]:
        if isinstance(current,list):current=current[part]
        elif isinstance(current,dict):current=current.get(part)
        else:return
        if current is None:return
    if isinstance(current,dict):current.pop(parts[-1],None)

def public_paths(component, mapping):
    paths=[p for k, ps in mapping['bindings'].items() if k in SECRET_KEYS or k==KEY for p in ps if not p.startswith('runtime.configuration.')]
    if component=='ai-service':paths.append('security.grpc-auth.shared-secret')
    if component in ('user-service','pay-service'):paths.append('security.grpc.shared-secret')
    return list(dict.fromkeys(paths))

def transform(component, application, environment, mapping, shared):
    validate_secret(shared)
    doc=yaml.safe_load(application)
    if not isinstance(doc,dict):raise ConfigError('application.yml must be a mapping')
    values=parse_env(environment)
    for path in public_paths(component,mapping):set_property(doc,path,'${'+KEY+'}')
    for path,node in list(walk_nodes(yaml.compose(application))):
        if not isinstance(node.value,str):continue
        refs=list(placeholders(node.value))
        if any(key in SECRET_KEYS for _,_,key,_ in refs):
            value=node.value
            for start,end,key,_ in reversed(refs):
                if key in SECRET_KEYS:value=value[:start]+'${'+KEY+'}'+value[end:]
            set_property(doc,path,value)
        elif any(key in LEGACY_KEYS or obsolete(key) for _,_,key,_ in refs) and component!='ai-service':
            if ('.callers.' in path or '.callers[' in path) and path.endswith('.secret'):remove_property(doc,path)
            elif any(key in LEGACY_KEYS for _,_,key,_ in refs):remove_property(doc,path)
    if component=='ai-service':
        old=get_property(doc,'security.grpc-auth.callers') or values.get('GRPC_AUTH_CALLERS','')
        names=get_property(doc,'security.grpc-auth.bootstrap-callers') or []
        if isinstance(old,str) and not old.startswith('${GRPC_AUTH_CALLERS'):
            names=list(dict.fromkeys([*names,*[x.split(':',1)[0].strip() for x in old.split(',') if ':' in x]]))
        set_property(doc,'security.grpc-auth.bootstrap-callers',names)
        remove_property(doc,'security.grpc-auth.callers')
    if component in ('user-service','pay-service'):
        for caller in get_property(doc,'security.grpc.service-jwt.callers') or []:caller.pop('secret',None)
        remove_property(doc,'security.grpc.jwt')
        if component=='pay-service':remove_property(doc,'security.grpc.internal-client')
        remove_property(doc,'security.grpc.internal-token')
        remove_property(doc,'security.grpc.legacy-internal-token-enabled')
        remove_property(doc,'billing.onboarding.grpc.legacy-static-token')
    for key in list(values):
        if obsolete(key):values.pop(key)
    runtime=get_property(doc,'runtime.configuration')
    if isinstance(runtime,dict):
        for key in list(runtime):
            if obsolete(key):runtime.pop(key)
    values[KEY]=shared
    # Keyring entries are handled by the migration coordinator with source identity evidence.
    text=yaml.safe_dump(doc,sort_keys=False,allow_unicode=True,width=120)
    for _,node in walk_nodes(yaml.compose(text)):
        if isinstance(node.value,str) and any(obsolete(key) for _,_,key,_ in placeholders(node.value)):
            raise ConfigError('obsolete public gRPC secret reference remains')
    return text,encode_env(values)

def mapping_for_shared(component,mapping):
    paths=public_paths(component,mapping)
    result=json.loads(json.dumps(mapping))
    for key in list(result['bindings']):
        if obsolete(key):result['bindings'].pop(key)
    for name,targets in list(result['bindings'].items()):
        result['bindings'][name]=[p for p in targets if not p.startswith('security.grpc.internal-client.')]
        if not result['bindings'][name]:result['bindings'].pop(name)
    result['bindings'][KEY]=paths
    if component=='ai-service':result['bindings']['GRPC_BOOTSTRAP_CALLERS']=['security.grpc-auth.bootstrap-callers']
    return result

def apply_group(changes, read, write, backup):
    """Read back every write; roll the whole environment back on any failure."""
    import hashlib
    from config_pair import atomic_write
    originals={identity:read(identity) for identity,_ in changes}
    for identity,content in originals.items():
        destination=backup/(hashlib.sha256(identity.encode()).hexdigest()+'.before')
        if not destination.exists():atomic_write(destination,content)
    attempted=[]
    try:
        for identity,content in changes:
            if content==originals[identity]:continue
            attempted.append(identity)
            write(identity,content)
            if read(identity)!=content:raise ConfigError('shared-key configuration read-back failed')
        if any(read(identity)!=content for identity,content in changes):
            raise ConfigError('shared-key environment verification failed')
    except BaseException:
        failures=[]
        for identity in reversed(attempted):
            try:
                write(identity,originals[identity])
                if read(identity)!=originals[identity]:failures.append(identity)
            except BaseException:failures.append(identity)
        if failures:raise ConfigError('shared-key rollback incomplete; protected backups retained') from None
        raise
    return {'changed_files':len(attempted),'verified_files':len(changes)}
