[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [string]$EnvironmentFile = (Join-Path $(if ($env:AIENIE_RUNTIME_ROOT) { $env:AIENIE_RUNTIME_ROOT } else { Join-Path $PSScriptRoot '../../../../aienie-runtime' }) 'private/app-secrets/aisocialgame.env'),
    [string]$ApprovalFile,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$ModelKey,
    [ValidateSet('LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX')][string]$ThinkingEffort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7 -or $PSVersionTable.Platform -ne 'Win32NT') {
    throw 'Undercover speech validation requires Windows PowerShell 7.'
}
if ([bool]$ModelKey -ne [bool]$ThinkingEffort) {
    throw 'Specify ModelKey and ThinkingEffort together for a comparison model override.'
}
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$evidence = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
if ($evidence -eq $projectRoot -or $evidence.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use an existing evidence directory outside the checkout.'
}

. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
. (Join-Path $projectRoot 'scripts/config-pair/ConfigurationPair.ps1')
. (Join-Path $PSScriptRoot 'LocalGrpcTrust.ps1')
$null = Resolve-SharedMySqlTarget $EnvironmentFile
$settings = Read-ConfigPairValues -ProjectRoot $projectRoot -EnvironmentFile $EnvironmentFile
$privateKeys = @('GRPC_SHARED_SECRET', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'APP_ADMIN_PASSWORD_HASH', 'ADMIN_TOTP_ENCRYPTION_KEY_V1')
foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $EnvironmentFile).Path)) {
    if ($line -match '^\s*(?:export\s+)?(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=(?<value>.*)$' -and $Matches.name -in $privateKeys) {
        $name = $Matches.name
        $value = $Matches.value
        if ($value -match '^(?<quote>["''])(.*)\k<quote>$') { $value = $value.Substring(1, $value.Length - 2) }
        $settings[$name] = $value
    }
}
$settings.ENV = 'local'
$settings.APP_ENV = 'local'
$settings.AIENIE_RUNTIME_PLANE = 'windows-local'
$settings.AIENIE_APPLICATION_FILE = ([Uri](Resolve-Path -LiteralPath ($EnvironmentFile + '.application.yml')).Path).AbsoluteUri
$settings.GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION = Get-LocalGrpcTrustUri
$settings.LOGGING_FILE_NAME = ''
$settings.LOGGING_LEVEL_ROOT = 'WARN'

$mode = if ($ApprovalFile) { 'compare' } else { 'quote' }
$testArgs = @('-q', '-f', (Join-Path $projectRoot 'backend/pom.xml'), '-Dtest=UndercoverSpeechComparisonRealTest',
    "-Dundercover.speech.mode=$mode", "-Dundercover.speech.evidence=$evidence")
if ($ApprovalFile) { $testArgs += "-Dundercover.speech.approval=$((Resolve-Path -LiteralPath $ApprovalFile).Path)" }
if ($ModelKey) { $testArgs += @("-Dundercover.speech.model=$ModelKey", "-Dundercover.speech.effort=$ThinkingEffort") }

$previous = @{}
try {
    foreach ($name in [Environment]::GetEnvironmentVariables('Process').Keys) {
        if ($name -match '^(APP_|AI_|AIENIE_|GRPC_|SPRING_|USER_GRPC_|BILLING_GRPC_|SSO_|LOGGING_|SERVER_|ADMIN_)' -or
                $name -in @('ENV', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
    }
    foreach ($entry in $settings.GetEnumerator()) {
        if (!$previous.ContainsKey($entry.Key)) { $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process') }
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    & mvn.cmd @testArgs test *> $null
    if ($LASTEXITCODE) { throw "Undercover speech $mode stopped. Review the targeted test report and any reserved request IDs." }
} finally {
    foreach ($entry in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
}
