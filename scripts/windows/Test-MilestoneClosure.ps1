[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BudgetFile,
    [Parameter(Mandatory)][string]$ManifestFile,
    [ValidateSet('Preflight','Pilot','Remaining')][string]$Phase='Preflight',
    [string]$EnvironmentFile, [string]$ApprovalFile, [string]$EvidenceFile,
    [string]$PilotEvidenceFile, [string]$PilotReviewFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'Requires PowerShell 7 on Windows.' }
. (Join-Path $PSScriptRoot 'LocalGrpcTrust.ps1')
$localGrpcTrust = Get-LocalGrpcTrustUri
$closureRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
function Resolve-PrivateClosurePath([string]$Path,[bool]$Existing) {
    if (-not [IO.Path]::IsPathFullyQualified($Path)) { throw 'External paths must be absolute.' }
    $resolved=[IO.Path]::GetFullPath($Path)
    if ($resolved.StartsWith($closureRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Keep credentials, grants, ledgers and raw evidence outside the checkout.' }
    $parent=Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($resolved))
    while($null -ne $parent) { if($parent.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse paths are not supported.'}; $parent=$parent.Parent }
    if($Existing) { $item=Get-Item -LiteralPath $resolved; if($item.PSIsContainer -or $item.Length -gt 32000000 -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid bounded input file.'} }
    elseif(Test-Path -LiteralPath $resolved){throw 'Evidence output must be new.'}
    return $resolved
}
$closureLedger=Resolve-PrivateClosurePath $BudgetFile $true
$closureManifest=Get-Content -LiteralPath $ManifestFile -Raw | ConvertFrom-Json -AsHashtable
if($closureManifest.evaluationSchemaVersion -ne 2 -or $closureManifest.evaluationSetVersion -cne 'closure-v2' -or $closureManifest.evidenceKind -cne 'REAL_MODEL' -or -not $closureManifest.batchId -or -not $closureManifest.buildId -or $closureManifest.sampleCount -ne 180 -or $closureManifest.maxDiscreteCalls -ne 360 -or $closureManifest.promptVersion -cne 'social-v2.8' -or $closureManifest.inputFormatVersion -ne 3 -or $closureManifest.memoryFormatVersion -ne 4 -or -not $closureManifest.sourceFingerprint){throw 'A current frozen 180-sample manifest is required.'}
$ledgerSummary=& python.exe (Join-Path $PSScriptRoot 'support/closure_preflight.py') --ledger-summary $closureLedger
if($LASTEXITCODE -ne 0){throw 'Invalid cumulative journal; reconcile without resetting it.'}
$closureConsumed=($ledgerSummary | ConvertFrom-Json).consumed
$closureJar=Get-ChildItem (Join-Path $closureRoot 'backend/target') -Filter '*.jar' | Select-Object -First 1
$closureIdentity=& python.exe (Join-Path $PSScriptRoot 'support/closure_identity.py') verify $closureRoot --jar $closureJar.FullName
if($LASTEXITCODE -ne 0){throw 'Build and freeze the current source before collection.'}
$closureIdentity=$closureIdentity | ConvertFrom-Json -AsHashtable
if($closureIdentity.sourceFingerprint -cne $closureManifest.sourceFingerprint -or $closureIdentity.buildId -cne $closureManifest.buildId){throw 'Stale manifest or build.'}
if($Phase -eq 'Preflight') {
    [ordered]@{realCalls=0;comparisonConsumed=$closureConsumed;historicalComparisonLimit=90;historicalRemaining=90-$closureConsumed;requiredDiscreteHeadroom=360;minimumCumulativeComparisonLimit=$closureConsumed+360;pilotMaximumRequests=24;remainingMaximumRequests=336;liveGamesAndNames='EXCLUDED_SEPARATE_PERSISTENT_BUDGET_REQUIRES_FRESH_READ';sourceFingerprint=$closureManifest.sourceFingerprint;status='LEDGER_AND_BUILD_ONLY_NOT_ENVIRONMENT_READY';environmentPreflight='Test-ClosurePreflight.ps1';readyForExecution=$false} | ConvertTo-Json
    exit 0
}
$closureGrantPath=Resolve-PrivateClosurePath $ApprovalFile $true
$closureEnvPath=Resolve-PrivateClosurePath $EnvironmentFile $true
$closureOutput=Resolve-PrivateClosurePath $EvidenceFile $false
$closureGrant=Get-Content -LiteralPath $closureGrantPath -Raw | ConvertFrom-Json -AsHashtable
. (Join-Path $PSScriptRoot 'ClosureGrant.ps1')
Assert-ClosureGrant $closureGrant
$closureNeeded=if($Phase -eq 'Pilot'){24}else{336}
if($closureGrant.batchId -cne $closureManifest.batchId -or $closureGrant.buildId -cne $closureManifest.buildId -or $closureGrant.comparisonLedger -cne $closureLedger -or $closureGrant.sourceFingerprint -cne $closureManifest.sourceFingerprint -or [long]$closureGrant.cumulativeComparisonLimit-$closureConsumed -lt $closureNeeded){throw 'Grant does not cover this ledger, version and worst-case request count.'}
$closureValues=@{}
foreach($line in [IO.File]::ReadAllLines($closureEnvPath)) {
    if([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')){continue}
    if($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){throw 'Environment must contain literal NAME=value entries.'}
    $key=$Matches[1];$value=$Matches[2]
    if($closureValues.ContainsKey($key)){throw 'Duplicate environment setting.'}
    if($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))){$value=$value.Substring(1,$value.Length-2)}
    $closureValues[$key]=$value
}
foreach($entry in @{ENV='local';AI_GRPC_ADDR='static://localaiservice.testhut.top:22011';AI_GRPC_NEGOTIATION_TYPE='TLS';APP_AI_DEFAULT_MODEL='deepseek-flash'}.GetEnumerator()) {
    if($closureValues[$entry.Key] -cne $entry.Value){throw 'Noncanonical runtime, model or TLS configuration.'}
}
$closureCanonical=@{
    ENV='local'; APP_ENV='local'; AIENIE_RUNTIME_PLANE='windows-local'; APP_PROJECT_KEY='aisocialgame'
    AI_GRPC_ADDR='static://localaiservice.testhut.top:22011'; AI_GRPC_NEGOTIATION_TYPE='TLS'; GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION=$localGrpcTrust
    APP_EXTERNAL_AISERVICE_HMAC_CALLER=$closureValues.APP_EXTERNAL_AISERVICE_HMAC_CALLER; GRPC_SHARED_SECRET=$closureValues.GRPC_SHARED_SECRET
    APP_AI_DEFAULT_MODEL='deepseek-flash'; APP_AI_SYSTEM_USER_ID='85'; APP_GAME_SCHEDULER_ENABLED='false'; APP_DEMO_SEED_ENABLED='false';QDRANT_ENABLED='false'
    LOGGING_FILE_NAME='';LOGGING_LEVEL_ROOT='OFF';LOGGING_LEVEL_APP='OFF'; AI_MILESTONE_CLOSURE_REAL='1'
    AI_CLOSURE_MANIFEST=(Resolve-Path -LiteralPath $ManifestFile).Path;AI_CLOSURE_PHASE=$Phase.ToUpperInvariant();AI_CLOSURE_APPROVAL=$closureGrantPath;AI_REALISM_BUDGET_FILE=$closureLedger;AI_CLOSURE_EVIDENCE=$closureOutput
}
if($Phase -eq 'Remaining') {
    $closureCanonical.AI_CLOSURE_PILOT_EVIDENCE=Resolve-PrivateClosurePath $PilotEvidenceFile $true
    $closureCanonical.AI_CLOSURE_PILOT_REVIEW=Resolve-PrivateClosurePath $PilotReviewFile $true
}
$closurePrior=@{}
try {
    foreach($name in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
        if([string]$name -match '^(APP_|AI_|REAL_|REALISM_|E2E_|GRPC_|SPRING_|USER_GRPC_|BILLING_GRPC_|SSO_|QDRANT_|LOGGING_|SERVER_|ADMIN_)' -or [string]$name -in @('ENV','AUTH_MODE','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS')) {
            $closurePrior[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$null,'Process')
        }
    }
    foreach($entry in $closureCanonical.GetEnumerator()) {
        if(-not $closurePrior.ContainsKey($entry.Key)){$closurePrior[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key,'Process')}
        [Environment]::SetEnvironmentVariable($entry.Key,[string]$entry.Value,'Process')
    }
    & mvn.cmd -q -f (Join-Path $closureRoot 'backend/pom.xml') '-Dtest=MilestoneClosureRealIntegrationTest' '-DdisableXmlReport=true' '-Dsurefire.useFile=false' '-Dmaven.test.redirectTestOutputToFile=false' test *> $null
    if($LASTEXITCODE -ne 0){throw 'Collection stopped. Preserve external evidence and the cumulative ledger; no automatic retry.'}
    if(-not (Test-Path -LiteralPath $closureOutput)){throw 'No evidence produced; a skipped test does not pass.'}
    $closureResult=Get-Content -LiteralPath $closureOutput -Raw | ConvertFrom-Json -AsHashtable
    if($closureResult.status -notin @('COLLECTED_REQUIRES_REVIEW','PILOT_REQUIRES_REVIEW')){throw 'Incomplete collection.'}
    Write-Host 'Collected evidence requires coding-agent review. This is not an L4 pass.'
} finally {
    foreach($entry in $closurePrior.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
}
