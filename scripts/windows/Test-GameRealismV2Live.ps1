[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TokensFile,
    [Parameter(Mandatory)][string]$EvidenceFile,
    [ValidateSet('undercover:1','undercover:3','werewolf:1','werewolf:3','turtle_soup:1','turtle_soup:3')]
    [string[]]$ScenarioIds = @(),
    [string]$ResumeEvidenceFile = '',
    [string]$ClosureApprovalFile = '',
    [string]$ClosureManifestFile = '',
    [string]$SoupAnswerFile = ''
)

. (Join-Path $PSScriptRoot 'ProjectNode.ps1')
Invoke-WithProjectNode {
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7 -or [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'The real-game acceptance adapter requires PowerShell 7 on Windows.'
}
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$inputPath = (Get-Item -LiteralPath $TokensFile -Force).FullName
$outputPath = [IO.Path]::GetFullPath($EvidenceFile)
if ($ResumeEvidenceFile) {
    $resume = Get-Item -LiteralPath $ResumeEvidenceFile -Force
    if ($resume.PSIsContainer -or ($resume.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $resume.Length -gt 16000000 -or $resume.FullName.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Resume evidence must be an ordinary external evidence file.'
    }
    $ResumeEvidenceFile = $resume.FullName
}
foreach ($path in @($inputPath, $outputPath)) {
    if ($path.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Private sessions and real-game evidence must remain outside the checkout.'
    }
}
if (Test-Path -LiteralPath $outputPath) { throw 'Choose a new EvidenceFile; previous runs must remain reviewable.' }
if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($outputPath)) -PathType Container)) {
    throw 'The evidence parent directory must already exist.'
}
$properties = @{}
foreach ($line in [IO.File]::ReadLines($inputPath)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -lt 1) { throw 'Unsupported private session properties format.' }
    $properties[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
}
if ($properties['status'] -cne 'COMPLETE' -or $properties['completedAccounts'] -cne '3') {
    throw 'Three completed ordinary-user application sessions are required.'
}
$tokens = @()
$users = @()
foreach ($index in 1..3) {
    $token = [string]$properties["account.$index.token"]
    $user = [string]$properties["account.$index.externalUserId"]
    if ($token -notmatch '^[A-Za-z0-9._-]{16,4096}$' -or $user -notmatch '^[1-9][0-9]*$') {
        throw 'Private session input has an invalid token or identity format.'
    }
    $tokens += $token
    $users += $user
}
if (@($tokens | Sort-Object -Unique).Count -ne 3 -or @($users | Sort-Object -Unique).Count -ne 3) {
    throw 'Use three different ordinary users and application sessions.'
}
$runtimePath = Get-VerifiedProjectNode
$settings = @{
    REAL_REALISM_V2 = '1'
    E2E_AUTH_TOKENS = $tokens -join ','
    REALISM_EVIDENCE_PATH = $outputPath
    REALISM_SCENARIOS = $ScenarioIds -join ','
    REALISM_RESUME_EVIDENCE = $ResumeEvidenceFile
    PLAYWRIGHT_BASE_URL = 'https://localsocialgame.testhut.top'
    PLAYWRIGHT_IGNORE_HTTPS_ERRORS = 'false'
    # Playwright's pw:api debug logger prints raw HTTP headers, including the
    # application session. Keep the child free of inherited diagnostic hooks.
    DEBUG = $null
    DEBUG_FILE = $null
    PWDEBUG = $null
    NODE_DEBUG = $null
    NODE_DEBUG_NATIVE = $null
    NODE_OPTIONS = $null
    # Use the verified Windows trust store, and override Node's global bypass.
    NODE_TLS_REJECT_UNAUTHORIZED = '1'
    NODE_USE_SYSTEM_CA = '1'
    NODE_EXTRA_CA_CERTS = $null
}
if($ClosureApprovalFile) {
    if($ResumeEvidenceFile){throw 'Final closure games must start fresh on the frozen version.'}
    $grant=Get-Content -LiteralPath $ClosureApprovalFile -Raw | ConvertFrom-Json -AsHashtable
    . (Join-Path $PSScriptRoot 'ClosureGrant.ps1')
    Assert-ClosureGrant $grant
    if(-not $grant.approvedBy -or -not $grant.approvalReference -or $grant.model -cne 'deepseek-flash' -or [DateTimeOffset]::Parse($grant.expiresAt) -le [DateTimeOffset]::UtcNow){throw 'A current explicit closure grant is required.'}
    if($grant.cumulativeLiveLimit -lt 1 -or -not $grant.liveBudgetRunId -or -not $grant.sourceFingerprint){throw 'Grant must identify the existing durable budget and frozen source.'}
    $manifest=Get-Content -LiteralPath $ClosureManifestFile -Raw | ConvertFrom-Json -AsHashtable
    $closureJar=Get-ChildItem (Join-Path $repoRoot 'backend/target') -Filter '*.jar' | Select-Object -First 1
    $identity=& python.exe (Join-Path $PSScriptRoot 'support/closure_identity.py') verify $repoRoot --jar $closureJar.FullName
    if($LASTEXITCODE -ne 0){throw 'Current frozen build verification failed.'}
    $identity=$identity | ConvertFrom-Json -AsHashtable
    if($manifest.evaluationSchemaVersion -ne 2 -or $manifest.evaluationSetVersion -cne 'closure-v2' -or $manifest.evidenceKind -cne 'REAL_MODEL' -or -not $manifest.batchId){throw 'Current real evaluation manifest required.'}
    foreach($key in @('sourceFingerprint','buildId')){if($identity[$key] -cne $manifest[$key] -or $grant[$key] -cne $manifest[$key]){throw 'Build, manifest and grant must match.'}}
    if($grant.batchId -cne $manifest.batchId){throw 'Grant batch mismatch.'}
    $settings.REALISM_BATCH_ID=[string]$manifest.batchId; $settings.REALISM_BUILD_ID=[string]$manifest.buildId
    $answerPath=(Get-Item -LiteralPath $SoupAnswerFile).FullName
    if($answerPath.StartsWith($repoRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Keep frozen acceptance answers outside the checkout.'}
    $answer=[IO.File]::ReadAllText($answerPath).Trim()
    if($answer.Length -lt 10 -or $answer.Length -gt 1000){throw 'A reviewed, bounded acceptance answer is required.'}
    $settings.REALISM_CLOSURE='1'; $settings.REALISM_SOUP_ANSWER=$answer
    $settings.REALISM_LIVE_LIMIT=[string]$grant.cumulativeLiveLimit; $settings.REALISM_LIVE_RUN_ID=[string]$grant.liveBudgetRunId
    $settings.REALISM_SOURCE_FINGERPRINT=[string]$grant.sourceFingerprint
}
$previous = @{}
try {
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    Push-Location (Join-Path $repoRoot 'frontend')
    try {
        $selectedCount = if ($ScenarioIds.Count) { $ScenarioIds.Count } else { 6 }
        Write-Host "Running $selectedCount ordinary-player API games with verified HTTPS and the persistent server budget."
        if((Get-VerifiedProjectNode) -cne $runtimePath){throw 'Acceptance Node executable changed.'}
        & $runtimePath './node_modules/@playwright/test/cli.js' test 'tests/realism-v2.spec.ts' '--workers=1' '--reporter=line'
        if ($LASTEXITCODE -ne 0) { throw "Real-game acceptance failed with exit code $LASTEXITCODE. Review the external evidence." }
    } finally { Pop-Location }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}

}
