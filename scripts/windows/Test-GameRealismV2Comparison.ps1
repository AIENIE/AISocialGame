[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    # Always pass the SAME external ledger for every attempt, including failed or interrupted runs.
    [Parameter(Mandatory)][string]$BudgetFile,
    [string[]]$ScenarioIds = @(),
    # Keep the caller limited to aisocialgame/TEXT; use the first budgeted scenario instead of ListModels.
    [switch]$ProjectScopedPreflight,
    # Use only with current backend classes and copied resources from the completed L2 gate.
    [switch]$UseCompiledTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7 -or
        [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Comparison acceptance requires PowerShell 7 or newer on Windows.'
}
$comparisonRepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'LocalGrpcTrust.ps1')
$localGrpcTrust = Get-LocalGrpcTrustUri
$comparisonBackend = Join-Path $comparisonRepoRoot 'backend'

function Assert-OutsideCheckout([string]$Path) {
    if ($Path -ieq $comparisonRepoRoot -or
            $Path.StartsWith($comparisonRepoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Comparison inputs, evidence and the cumulative ledger must stay outside the checkout.'
    }
    # Reject ancestor junctions/symlinks so lexical containment cannot select a different target.
    $directory = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($Path))
    while ($null -ne $directory) {
        if (($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Comparison paths must not traverse a reparse point.'
        }
        $directory = $directory.Parent
    }
}

function Resolve-ComparisonPath([string]$Path, [bool]$MustExist, [bool]$MustBeNew) {
    if (-not [IO.Path]::IsPathFullyQualified($Path)) { throw 'Comparison paths must be absolute.' }
    $resolved = [IO.Path]::GetFullPath($Path)
    $parent = Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($resolved)) -Force -ErrorAction Stop
    if (-not $parent.PSIsContainer) { throw 'Comparison path parent must be an existing directory.' }
    Assert-OutsideCheckout $resolved
    $exists = Test-Path -LiteralPath $resolved
    if ($MustBeNew -and $exists) { throw 'EvidenceDirectory must be new; existing evidence is never overwritten.' }
    if ($MustExist -and -not $exists) { throw 'The private environment file is missing.' }
    if ($exists) {
        $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.Length -gt 1000000) {
            throw 'Comparison file inputs must be regular files no larger than 1 MB.'
        }
    }
    return $resolved
}

function Read-ComparisonEnvironment([string]$Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $match = [regex]::Match($line, '^\s*(?:export\s+)?(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=(?<value>.*)$')
        if (-not $match.Success) { throw 'Private environment accepts literal NAME=value entries only.' }
        $name = $match.Groups['name'].Value
        if ($values.ContainsKey($name)) { throw 'Private environment contains a duplicate setting.' }
        $value = $match.Groups['value'].Value
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        $values[$name] = $value
    }
    return $values
}

function Assert-ComparisonLocal([System.Collections.IDictionary]$Values) {
    foreach ($name in @('ENV', 'APP_ENV', 'SPRING_PROFILES_ACTIVE')) {
        if ($Values.Contains($name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$name]) -and
                [string]$Values[$name] -cne 'local') { throw 'Comparison adapter rejects a non-local runtime environment.' }
    }
    if ($Values.Contains('AIENIE_RUNTIME_PLANE') -and
            -not [string]::IsNullOrWhiteSpace([string]$Values['AIENIE_RUNTIME_PLANE']) -and
            [string]$Values['AIENIE_RUNTIME_PLANE'] -cne 'windows-local') {
        throw 'Comparison adapter requires the Windows-local runtime plane.'
    }
    foreach ($expected in @(
            @{ Name = 'AI_GRPC_ADDR'; Value = 'static://localaiservice.testhut.top:22011' },
            @{ Name = 'AI_GRPC_NEGOTIATION_TYPE'; Value = 'TLS' },
            @{ Name = 'GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION'; Value = $localGrpcTrust })) {
        if ($Values.Contains($expected.Name) -and [string]$Values[$expected.Name] -cne $expected.Value) {
            throw 'Comparison adapter rejects a noncanonical AI gateway target or TLS setting.'
        }
    }
}

function Get-ComparisonLedgerCount([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return 0 }
    $content = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) { throw 'Cumulative comparison ledger is incomplete; it must be reconciled, never reset.' }
    if ($content.Length -eq 0) { return 0 }
    $count = 0
    # ReadAllText has already closed the handle; a rejected row must not leave the ledger locked.
    foreach ($line in ($content.Substring(0, $content.Length - 1) -split "`n")) {
        try { $reservation = ConvertFrom-Json -InputObject $line -AsHashtable -ErrorAction Stop }
        catch { throw 'Cumulative comparison ledger is invalid; it must be reconciled, never reset.' }
        if (-not ($reservation -is [System.Collections.IDictionary]) -or
                -not $reservation.Contains('comparisonAttempt') -or -not $reservation.Contains('event') -or
                [string]$reservation['comparisonAttempt'] -notmatch '^[1-9][0-9]{0,2}$' -or
                [int]$reservation['comparisonAttempt'] -ne ($count + 1) -or
                [string]$reservation['event'] -cne 'ATTEMPT_RESERVED') {
            throw 'Cumulative comparison ledger sequence is invalid; automatic reset is forbidden.'
        }
        $count++
    }
    if ($count -ge 90) { throw 'The cumulative comparison allowance is exhausted; no model request was started.' }
    return $count
}

function Assert-CompiledComparison {
    foreach ($name in @('AiRealismComparisonIntegrationTest', 'AiRealismScenarioFixtures', 'AiRealismComparisonLedger', 'AiRealismGrpcDiagnostics')) {
        $relative = 'com/aisocialgame/service/ai/v2/' + $name
        $source = Get-Item -LiteralPath (Join-Path $comparisonBackend ('src/test/java/' + $relative + '.java'))
        $compiled = Get-Item -LiteralPath (Join-Path $comparisonBackend ('target/test-classes/' + $relative + '.class')) -ErrorAction SilentlyContinue
        if ($null -eq $compiled -or $compiled.LastWriteTimeUtc -lt $source.LastWriteTimeUtc) {
            throw 'Compiled comparison tests are missing or stale. Compile the current test sources before using -UseCompiledTests.'
        }
    }
    $mainSources = Join-Path $comparisonBackend 'src/main/java'
    foreach ($source in Get-ChildItem -LiteralPath $mainSources -File -Recurse -Filter '*.java') {
        $relative = [IO.Path]::GetRelativePath($mainSources, $source.FullName)
        $compiled = Get-Item -LiteralPath (Join-Path $comparisonBackend ('target/classes/' + [IO.Path]::ChangeExtension($relative, '.class'))) -ErrorAction SilentlyContinue
        if ($null -eq $compiled -or $compiled.LastWriteTimeUtc -lt $source.LastWriteTimeUtc) {
            throw 'Compiled backend classes are missing or stale. Run the current backend L2 gate before using -UseCompiledTests.'
        }
    }
    foreach ($pair in @(@('src/main/resources', 'target/classes'), @('src/test/resources', 'target/test-classes'))) {
        $resourceRoot = Join-Path $comparisonBackend $pair[0]
        foreach ($source in Get-ChildItem -LiteralPath $resourceRoot -File -Recurse) {
            $copied = Join-Path (Join-Path $comparisonBackend $pair[1]) ([IO.Path]::GetRelativePath($resourceRoot, $source.FullName))
            if (-not (Test-Path -LiteralPath $copied -PathType Leaf) -or
                    (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash) {
                throw 'Compiled comparison resources differ from the sources. Refresh backend resources before using -UseCompiledTests.'
            }
        }
    }
}

$comparisonEnvironment = Resolve-ComparisonPath $EnvironmentFile $true $false
$comparisonEvidence = Resolve-ComparisonPath $EvidenceDirectory $false $true
$comparisonLedger = Resolve-ComparisonPath $BudgetFile $false $false
if ($comparisonEnvironment -ieq $comparisonLedger -or
        $comparisonLedger -ieq $comparisonEvidence -or
        $comparisonLedger.StartsWith($comparisonEvidence + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The cumulative ledger must be separate from the environment file and outlive the individual evidence directory.'
}
$comparisonValues = Read-ComparisonEnvironment $comparisonEnvironment
$comparisonInherited = @{}
foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) { $comparisonInherited[[string]$entry.Key] = [string]$entry.Value }
Assert-ComparisonLocal $comparisonInherited
Assert-ComparisonLocal $comparisonValues
foreach ($settings in @($comparisonInherited, $comparisonValues)) {
    if ($settings.ContainsKey('AI_REALISM_BUDGET_FILE') -and
            -not [string]::IsNullOrWhiteSpace([string]$settings['AI_REALISM_BUDGET_FILE']) -and
            [IO.Path]::GetFullPath([string]$settings['AI_REALISM_BUDGET_FILE']) -ine $comparisonLedger) {
        throw 'BudgetFile differs from the already configured cumulative ledger. Reuse the same ledger; changing it would reset accounting.'
    }
}
foreach ($name in @('APP_EXTERNAL_AISERVICE_HMAC_CALLER', 'APP_EXTERNAL_AISERVICE_HMAC_SECRET')) {
    $value = [string]$comparisonValues[$name]
    if ([string]::IsNullOrWhiteSpace($value) -or $value -cne $value.Trim() -or
            $value -match '[\x00-\x1F\x7F]' -or [Text.Encoding]::UTF8.GetByteCount($value) -gt 4096) {
        throw 'The private environment must provide a nonempty AI HMAC caller and secret without surrounding whitespace or controls.'
    }
}
$comparisonPriorAttempts = Get-ComparisonLedgerCount $comparisonLedger
$comparisonGoal = 'test'
if ($UseCompiledTests) { Assert-CompiledComparison; $comparisonGoal = 'surefire:test' }
$comparisonMaven = (Get-Command 'mvn.cmd' -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$comparisonModel = if ($comparisonValues.ContainsKey('APP_AI_DEFAULT_MODEL')) { [string]$comparisonValues['APP_AI_DEFAULT_MODEL'] } else { '' }

# Only AI credentials and the existing model choice enter this test process. H2 and the test profile
# retain isolated persistence; no application server, user login or billing bootstrap is started.
$comparisonCanonical = [ordered]@{
    ENV = 'local'; APP_ENV = 'local'; AIENIE_RUNTIME_PLANE = 'windows-local'; APP_PROJECT_KEY = 'aisocialgame'
    AI_GRPC_ADDR = 'static://localaiservice.testhut.top:22011'; AI_GRPC_NEGOTIATION_TYPE = 'TLS'
    GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION = $localGrpcTrust
    APP_EXTERNAL_AISERVICE_HMAC_CALLER = [string]$comparisonValues['APP_EXTERNAL_AISERVICE_HMAC_CALLER']
    APP_EXTERNAL_AISERVICE_HMAC_SECRET = [string]$comparisonValues['APP_EXTERNAL_AISERVICE_HMAC_SECRET']
    APP_AI_DEFAULT_MODEL = $comparisonModel; APP_AI_SYSTEM_USER_ID = '85'
    APP_GAME_SCHEDULER_ENABLED = 'false'; APP_DEMO_SEED_ENABLED = 'false'; QDRANT_ENABLED = 'false'
    LOGGING_FILE_NAME = ''; LOGGING_LEVEL_ROOT = 'OFF'; LOGGING_LEVEL_APP = 'OFF'
    AI_REALISM_COMPARISON = '1'; AI_REALISM_EVIDENCE_DIR = $comparisonEvidence; AI_REALISM_BUDGET_FILE = $comparisonLedger
    AI_REALISM_PROJECT_SCOPED_PREFLIGHT = $(if ($ProjectScopedPreflight) { '1' } else { '0' })
    AI_REALISM_SCENARIOS = $ScenarioIds -join ','
}
$comparisonChanged = @{}
function Set-ComparisonEnvironment([string]$Name, [AllowNull()][string]$Value) {
    if (-not $comparisonChanged.ContainsKey($Name)) { $comparisonChanged[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
try {
    foreach ($name in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
        $upper = ([string]$name).ToUpperInvariant()
        if ($upper -match '^(APP_|AI_|REAL_|REALISM_|E2E_|GRPC_|SPRING_|USER_GRPC_|BILLING_GRPC_|SSO_|QDRANT_|LOGGING_|SERVER_|ADMIN_)' -or
                $upper -in @('ENV', 'APP_ENV', 'AUTH_MODE', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS', 'NODE_OPTIONS')) {
            Set-ComparisonEnvironment ([string]$name) $null
        }
    }
    foreach ($entry in $comparisonCanonical.GetEnumerator()) { Set-ComparisonEnvironment ([string]$entry.Key) ([string]$entry.Value) }
    $expectedCount = if ($ScenarioIds.Count -gt 0) { $ScenarioIds.Count } else { 30 }
    Write-Host "Starting local comparison: $expectedCount scenarios; previous cumulative attempts=$comparisonPriorAttempts; ceiling=90; system user=85."
    # Suppress arbitrary Maven/framework diagnostics. Credentials are environment-only, never arguments.
    # No XML/system-property dump or Surefire output file is produced; safe evidence comes from the test.
    $comparisonMavenArguments = @('-q', '-f', (Join-Path $comparisonBackend 'pom.xml'),
        '-Dtest=com.aisocialgame.service.ai.v2.AiRealismComparisonIntegrationTest',
        '-DfailIfNoTests=true', '-DdisableXmlReport=true', '-Dsurefire.useFile=false',
        '-Dmaven.test.redirectTestOutputToFile=false', $comparisonGoal)
    Push-Location -LiteralPath $comparisonBackend
    try {
        & $comparisonMaven @comparisonMavenArguments *> $null
        $comparisonExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($comparisonExit -ne 0) {
        throw "Comparison failed with Maven exit code $comparisonExit. Inspect external preflight.json and summary.json; do not reset the cumulative ledger."
    }
    $summaryPath = Join-Path $comparisonEvidence 'summary.json'
    if (-not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) { throw 'Comparison returned no summary; a skipped test is not acceptance evidence.' }
    try { $summary = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($summaryPath)) -AsHashtable -ErrorAction Stop }
    catch { throw 'Comparison produced an unreadable summary. Existing evidence and reservations were retained.' }
    $comparisonPreflightAccepted = if ($ProjectScopedPreflight) {
        $summary['preflightStatus'] -ceq 'SCOPED_GENERATION_CONFIRMED' -and
            $summary['completedResponses'] -gt 0 -and $summary['successfulGenerationResponses'] -gt 0 -and $summary['callOrJournalFailures'] -eq 0
    } else { $summary['preflightStatus'] -ceq 'MODEL_CATALOG_READY' }
    if ($summary['outcome'] -cne 'COLLECTED_REQUIRES_MANUAL_REVIEW' -or $summary['completedScenarios'] -ne $expectedCount -or
            -not $comparisonPreflightAccepted -or $summary['cumulativeComparisonAttempts'] -gt 90) {
        throw 'Comparison did not collect all 30 scenarios within the cumulative ceiling; inspect the external evidence.'
    }
    Write-Host ('Collected {0} comparisons; cumulative attempts={1}/90. Evidence requires manual review; no naturalness score is claimed.' -f $expectedCount, [int]$summary['cumulativeComparisonAttempts'])
} finally {
    foreach ($entry in $comparisonChanged.GetEnumerator()) { [Environment]::SetEnvironmentVariable([string]$entry.Key, $entry.Value, 'Process') }
}
