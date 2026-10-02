[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$AccountsFile,
    [Parameter(Mandatory)][string]$OutputFile,
    # Use only immediately after the backend L2 build/test gate has passed.
    [switch]$UseCompiledTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7 -or
        [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Local account preparation requires PowerShell 7 or newer on Windows.'
}
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'LocalGrpcTrust.ps1')
$localGrpcTrust = Get-LocalGrpcTrustUri

function Test-ProtectedProcessVariable([string]$Name) {
    $upper = $Name.ToUpperInvariant()
    return $upper -in @('PATH', 'PATHEXT', 'COMSPEC', 'SYSTEMROOT', 'WINDIR', 'PSMODULEPATH',
        'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'NODE_OPTIONS', 'SPRING_APPLICATION_JSON') -or
        $upper.StartsWith('SPRING_CONFIG_', [StringComparison]::Ordinal)
}

function Assert-PrivateFile([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Private inputs must be regular files.'
    }
    if ($item.FullName.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Private account and runtime inputs must stay outside the checkout.'
    }
    return $item.FullName
}

function Read-PrivateEnvironment([string]$Path) {
    $result = @{}
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadLines($Path)) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $match = [Regex]::Match($line, '^\s*(?:export\s+)?(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=(?<value>.*)$')
        if (-not $match.Success) { throw "Environment file has an unsupported line at $lineNumber. Use literal NAME=value entries only." }
        $name = $match.Groups['name'].Value
        if (Test-ProtectedProcessVariable $name) { throw "Environment file cannot set protected process variable '$name'." }
        if ($result.ContainsKey($name)) { throw "Environment file defines '$name' more than once." }
        $value = $match.Groups['value'].Value
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        $result[$name] = $value
    }
    return $result
}

function Assert-LocalOnlyEnvironment([System.Collections.IDictionary]$Values) {
    foreach ($name in @('ENV', 'APP_ENV', 'SPRING_PROFILES_ACTIVE')) {
        if ($Values.Contains($name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$name]) -and
                [string]$Values[$name] -cne 'local') { throw "Account preparation rejects non-local $name values." }
    }
    if ($Values.Contains('AIENIE_RUNTIME_PLANE') -and
            -not [string]::IsNullOrWhiteSpace([string]$Values['AIENIE_RUNTIME_PLANE']) -and
            [string]$Values['AIENIE_RUNTIME_PLANE'] -cne 'windows-local') {
        throw 'Account preparation rejects a non-windows-local runtime plane.'
    }
}

function Assert-UserServiceJwtEnvironment([System.Collections.IDictionary]$Values) {
    function Read-Value([string]$Name) {
        if ($Values.Contains($Name) -and $null -ne $Values[$Name]) { return [string]$Values[$Name] }
        return ''
    }
    if ((Read-Value 'APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN').Length -gt 0) {
        throw 'Legacy UserService internal tokens are not accepted.'
    }
    if ((Read-Value 'APP_EXTERNAL_GRPC_AUTH_REQUIRED') -eq 'false') { throw 'Real account preparation requires external gRPC authentication.' }
    $expected = [ordered]@{
        APP_EXTERNAL_USERSERVICE_JWT_CALLER_ID = 'aisocialgame'
        APP_EXTERNAL_USERSERVICE_JWT_ISSUER = 'aisocialgame'
        APP_EXTERNAL_USERSERVICE_JWT_AUDIENCE = 'aienie-userservice-grpc'
        APP_EXTERNAL_USERSERVICE_JWT_SCOPES = 'user.auth.session.read,user.directory.read,user.ban.read,user.ban.write'
    }
    foreach ($entry in $expected.GetEnumerator()) {
        if ((Read-Value $entry.Key) -cne $entry.Value) { throw "Invalid canonical UserService caller JWT setting: $($entry.Key)." }
    }
    $ttl = 0L
    if (-not [long]::TryParse((Read-Value 'APP_EXTERNAL_USERSERVICE_JWT_TTL_SECONDS'), [Globalization.NumberStyles]::None,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$ttl) -or $ttl -lt 30 -or $ttl -gt 900) {
        throw 'APP_EXTERNAL_USERSERVICE_JWT_TTL_SECONDS must be an integer between 30 and 900.'
    }
    $secret = Read-Value 'GRPC_SHARED_SECRET'
    $bytes = [Text.Encoding]::UTF8.GetByteCount($secret)
    $normalized = $secret.ToUpperInvariant()
    if ($bytes -lt 32 -or $bytes -gt 4096 -or $secret -cne $secret.Trim() -or $secret -match '[\x00-\x1F\x7F]' -or
            $normalized.Contains('REPLACE') -or $normalized.Contains('CHANGE_ME') -or $normalized.Contains('CHANGE-ME') -or
            $normalized.Contains('CHANGEME') -or $normalized.Contains('PLACEHOLDER') -or
            $normalized.StartsWith('<') -or $normalized.EndsWith('>')) {
        throw 'GRPC_SHARED_SECRET must contain 32..4096 non-placeholder UTF-8 bytes without boundary whitespace or controls.'
    }
}

$environmentPath = Assert-PrivateFile $EnvironmentFile
$accountsPath = Assert-PrivateFile $AccountsFile
$outputPath = [IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $outputPath) { throw 'OutputFile already exists; choose a new private file for this explicit attempt.' }
$outputParent = (Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($outputPath)) -Force).FullName
if ($outputParent -ine [IO.Path]::GetDirectoryName($accountsPath)) { throw 'OutputFile must stay in the same private directory as AccountsFile.' }
$values = Read-PrivateEnvironment $environmentPath
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$sharedTarget = Resolve-SharedMySqlTarget $environmentPath
$inherited = @{}
foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) { $inherited[[string]$entry.Key] = [string]$entry.Value }
Assert-LocalOnlyEnvironment $inherited
Assert-LocalOnlyEnvironment $values
Assert-UserServiceJwtEnvironment $values

# Canonical Windows-local settings match Start-Backend.ps1. WebEnvironment.MOCK binds no HTTP port.
$canonical = [ordered]@{
    ENV = 'local'; APP_ENV = 'local'; SPRING_PROFILES_ACTIVE = 'local'; AIENIE_RUNTIME_PLANE = 'windows-local'
    AUTH_MODE = 'password'; APP_PROJECT_KEY = 'aisocialgame'; SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = '11031'
    SPRING_DATASOURCE_URL = $sharedTarget.jdbcUrl
    SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
    SPRING_DATA_REDIS_HOST = 'localredis.testhut.top'; SPRING_DATA_REDIS_PORT = '26379'; SPRING_DATA_REDIS_SSL_ENABLED = 'false'
    QDRANT_HOST = 'http://localqdrant.testhut.top'; QDRANT_PORT = '26333'
    USER_GRPC_ADDR = 'static://localuserservice.testhut.top:22001'
    BILLING_GRPC_ADDR = 'static://localpayservice.testhut.top:22021'
    AI_GRPC_ADDR = 'static://localaiservice.testhut.top:22011'
    GRPC_CLIENT_USER_SECURITY_TRUST_CERT_COLLECTION = $localGrpcTrust; GRPC_CLIENT_BILLING_SECURITY_TRUST_CERT_COLLECTION = $localGrpcTrust
    GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION = $localGrpcTrust; USER_GRPC_NEGOTIATION_TYPE = 'TLS'
    BILLING_GRPC_NEGOTIATION_TYPE = 'TLS'; AI_GRPC_NEGOTIATION_TYPE = 'TLS'
    BILLING_GRPC_PLAINTEXT_ENABLED = 'false'; APP_SECURITY_ALLOW_PLAINTEXT_GRPC = 'false'
    APP_EXTERNAL_GRPC_AUTH_REQUIRED = 'true'; APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN = ''
    SSO_USER_SERVICE_BASE_URL = 'https://localuserservice.testhut.top'
    SSO_CALLBACK_URL = 'https://localsocialgame.testhut.top/sso/callback'
    APP_GAME_SCHEDULER_ENABLED = 'false'; APP_DEMO_SEED_ENABLED = 'false'
    AI_REALISM_LOGIN_BOOTSTRAP = '1'; AI_REALISM_ACCOUNTS_FILE = $accountsPath; AI_REALISM_TOKENS_FILE = $outputPath
}
$maven = (Get-Command -Name 'mvn.cmd' -CommandType Application -ErrorAction Stop | Select-Object -First 1).Path
$mavenGoal = 'test'
if ($UseCompiledTests) {
    $compiledTest = Join-Path $repoRoot 'backend/target/test-classes/com/aisocialgame/acceptance/LocalRealismLoginBootstrapTest.class'
    if (-not (Test-Path -LiteralPath $compiledTest -PathType Leaf)) { throw 'Run the backend L2 gate before using compiled acceptance tests.' }
    $mavenGoal = 'surefire:test'
}
$changed = @{}
function Set-PreparationEnvironment([string]$Name, [AllowNull()][string]$Value) {
    if (-not $changed.ContainsKey($Name)) { $changed[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
try {
    foreach ($name in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
        if ([string]$name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'NODE_OPTIONS', 'SPRING_APPLICATION_JSON') -or
                ([string]$name).StartsWith('SPRING_CONFIG_', [StringComparison]::OrdinalIgnoreCase)) {
            Set-PreparationEnvironment ([string]$name) $null
        }
    }
    foreach ($entry in $values.GetEnumerator()) { Set-PreparationEnvironment ([string]$entry.Key) ([string]$entry.Value) }
    foreach ($entry in $canonical.GetEnumerator()) { Set-PreparationEnvironment ([string]$entry.Key) ([string]$entry.Value) }
    Write-Host 'Preparing three ordinary local application sessions through real user login and existing billing initialization.'
    & $maven -q -f (Join-Path $repoRoot 'backend\pom.xml') '-Dtest=com.aisocialgame.acceptance.LocalRealismLoginBootstrapTest' '-DfailIfNoTests=true' $mavenGoal
    if ($LASTEXITCODE -ne 0) { throw "Local account preparation failed with Maven exit code $LASTEXITCODE. Any partial session output remains private." }
    Write-Host 'Preparation completed. Tokens are only in OutputFile; the original runtime environment file was not changed.'
} finally {
    foreach ($entry in $changed.GetEnumerator()) { [Environment]::SetEnvironmentVariable([string]$entry.Key, $entry.Value, 'Process') }
}
