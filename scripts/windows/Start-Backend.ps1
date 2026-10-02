[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path 'D:\project\aienie\aienie-runtime\private\app-secrets' 'aisocialgame.env'),
    [switch]$EnableBackendDebug
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7 -or $env:OS -ne 'Windows_NT') {
    throw 'Start-Backend.ps1 requires PowerShell 7 or newer on Windows.'
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

function Test-ProtectedProcessVariable([string]$Name) {
    $upperName = $Name.ToUpperInvariant()
    return $upperName -in @(
            'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
            'MAVEN_OPTS', 'SPRING_APPLICATION_JSON') -or
        $upperName.StartsWith('SPRING_CONFIG_', [StringComparison]::Ordinal)
}

function Assert-LocalOnlyEnvironment([System.Collections.IDictionary]$Values) {
    foreach ($name in @('ENV', 'APP_ENV', 'SPRING_PROFILES_ACTIVE')) {
        if (-not $Values.Contains($name)) { continue }
        $value = [string]$Values[$name]
        if (-not [string]::IsNullOrWhiteSpace($value) -and $value -cne 'local') {
            throw "Debug backend startup rejects non-local $name values."
        }
    }
    if ($Values.Contains('AIENIE_RUNTIME_PLANE')) {
        $runtimePlane = [string]$Values['AIENIE_RUNTIME_PLANE']
        if (-not [string]::IsNullOrWhiteSpace($runtimePlane) -and $runtimePlane -cne 'windows-local') {
            throw 'Debug backend startup rejects non-windows-local AIENIE_RUNTIME_PLANE values.'
        }
    }
}

function Read-PrivateEnvironment([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file was not found: $Path"
    }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Environment file must be a regular non-reparse file: $Path"
    }
    $blocked = @('PATH', 'PATHEXT', 'COMSPEC', 'SYSTEMROOT', 'WINDIR', 'PSMODULEPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'NODE_OPTIONS')
    $result = @{}
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadLines($item.FullName)) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $match = [Regex]::Match($line, '^\s*(?:export\s+)?(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=(?<value>.*)$')
        if (-not $match.Success) { throw "Environment file has an unsupported line at $lineNumber. Use literal NAME=value entries only." }
        $name = $match.Groups['name'].Value
        if ($blocked -contains $name.ToUpperInvariant() -or (Test-ProtectedProcessVariable $name)) {
            throw "Environment file cannot set protected process variable '$name'."
        }
        if ($result.ContainsKey($name)) { throw "Environment file defines '$name' more than once." }
        $value = $match.Groups['value'].Value
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        $result[$name] = $value
    }
    return $result
}

function Assert-UserServiceJwtEnvironment([System.Collections.IDictionary]$Values) {
    function Read-Value([string]$Name) {
        if ($Values.Contains($Name) -and $null -ne $Values[$Name]) { return [string]$Values[$Name] }
        return ''
    }

    $legacy = Read-Value 'APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN'
    if ($legacy.Length -gt 0) {
        throw 'APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN is legacy and must be absent or empty.'
    }
    if ((Read-Value 'APP_EXTERNAL_GRPC_AUTH_REQUIRED') -eq 'false') { return }

    $expected = [ordered]@{
        APP_EXTERNAL_USERSERVICE_JWT_CALLER_ID = 'aisocialgame'
        APP_EXTERNAL_USERSERVICE_JWT_ISSUER = 'aisocialgame'
        APP_EXTERNAL_USERSERVICE_JWT_AUDIENCE = 'aienie-userservice-grpc'
        APP_EXTERNAL_USERSERVICE_JWT_SCOPES = 'user.auth.session.read,user.directory.read,user.ban.read,user.ban.write'
    }
    foreach ($entry in $expected.GetEnumerator()) {
        if ((Read-Value $entry.Key) -cne $entry.Value) {
            throw "Invalid canonical UserService caller JWT setting: $($entry.Key)."
        }
    }

    $ttlText = Read-Value 'APP_EXTERNAL_USERSERVICE_JWT_TTL_SECONDS'
    $ttl = 0L
    if (-not [long]::TryParse($ttlText, [Globalization.NumberStyles]::None,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$ttl) -or $ttl -lt 30 -or $ttl -gt 900) {
        throw 'APP_EXTERNAL_USERSERVICE_JWT_TTL_SECONDS must be an integer between 30 and 900.'
    }

    $secret = Read-Value 'GRPC_SHARED_SECRET'
    $secretBytes = [Text.Encoding]::UTF8.GetByteCount($secret)
    $normalized = $secret.ToUpperInvariant()
    if ($secretBytes -lt 32 -or $secretBytes -gt 4096 -or $secret -cne $secret.Trim() -or
            $secret -match '[\x00-\x1F\x7F]' -or $normalized.Contains('REPLACE') -or
            $normalized.Contains('CHANGE_ME') -or $normalized.Contains('CHANGE-ME') -or
            $normalized.Contains('CHANGEME') -or
            $normalized.Contains('PLACEHOLDER') -or $normalized.StartsWith('<') -or $normalized.EndsWith('>')) {
        throw 'GRPC_SHARED_SECRET must contain 32..4096 non-placeholder UTF-8 bytes without boundary whitespace or controls.'
    }
}

function Test-PortListening([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try { $task = $client.ConnectAsync('127.0.0.1', $Port); return $task.Wait(1000) -and $client.Connected } catch { return $false } finally { $client.Dispose() }
}

$privateValues = Read-PrivateEnvironment $EnvironmentFile
. (Join-Path $repoRoot 'scripts\config-pair\ConfigurationPair.ps1')
$yamlValues = Read-ConfigPairValues -ProjectRoot $repoRoot -EnvironmentFile $EnvironmentFile
foreach ($key in $yamlValues.Keys) { $privateValues[$key] = $yamlValues[$key] }
. (Join-Path $PSScriptRoot 'LocalGrpcTrust.ps1')
$localGrpcTrust = Get-LocalGrpcTrustUri
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$sharedTarget = Resolve-SharedMySqlTarget $EnvironmentFile
$inheritedValues = @{}
foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) { $inheritedValues[[string]$entry.Key] = [string]$entry.Value }
Assert-LocalOnlyEnvironment $inheritedValues
Assert-LocalOnlyEnvironment $privateValues
Assert-UserServiceJwtEnvironment $privateValues

if (Test-PortListening 11031) {
    throw 'Port 11031 is already listening. Stop the owning process before starting the debug backend.'
}

foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'SPRING_APPLICATION_JSON',
        'APP_EXTERNAL_GRPC_AUTH_REQUIRED', 'APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN',
        'APP_EXTERNAL_USERSERVICE_JWT_CALLER_ID', 'APP_EXTERNAL_USERSERVICE_JWT_ISSUER',
        'GRPC_SHARED_SECRET', 'APP_EXTERNAL_USERSERVICE_JWT_AUDIENCE',
        'APP_EXTERNAL_USERSERVICE_JWT_TTL_SECONDS', 'APP_EXTERNAL_USERSERVICE_JWT_SCOPES')) {
    if ($null -ne [Environment]::GetEnvironmentVariable($name, 'Process')) {
        Write-Verbose "Removing inherited '$name' before applying the environment file."
        Remove-Item -Path ("Env:\" + $name) -ErrorAction SilentlyContinue
    }
}
foreach ($name in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
    if (Test-ProtectedProcessVariable ([string]$name)) {
        Write-Verbose "Removing inherited protected process variable '$name'."
        Remove-Item -Path ("Env:\" + $name) -ErrorAction SilentlyContinue
    }
}
foreach ($entry in $privateValues.GetEnumerator()) { [Environment]::SetEnvironmentVariable([string]$entry.Key, [string]$entry.Value, 'Process') }

$env:APP_ENV = 'local'
Import-ConfigPairValues -ProjectRoot $repoRoot -EnvironmentFile $EnvironmentFile
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:AIENIE_RUNTIME_PLANE = 'windows-local'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:VITE_LOCAL_BACKEND_PORT = '11031'
$env:USER_GRPC_NEGOTIATION_TYPE = 'TLS'
$env:BILLING_GRPC_NEGOTIATION_TYPE = 'TLS'
$env:AI_GRPC_NEGOTIATION_TYPE = 'TLS'

$backendPom = Join-Path $repoRoot 'backend\pom.xml'
$mvn = (Get-Command -Name 'mvn.cmd' -CommandType Application -ErrorAction Stop | Select-Object -First 1).Path

Write-Host 'Starting AISocialGame backend (debug) on http://127.0.0.1:11031 - press Ctrl+C to stop.'
$mavenArgs = @('-f', $backendPom, '-q', 'spring-boot:run')
if ($EnableBackendDebug) { $mavenArgs += '-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:51031' }
& $mvn @mavenArgs
$exitCode = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } else { 0 }
if ($exitCode -ne 0) { Write-Host "Backend exited with code $exitCode." }
exit $exitCode
