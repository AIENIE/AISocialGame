[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$project = Split-Path $repoRoot -Leaf
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('aienie-entry-' + [guid]::NewGuid().ToString('N'))
$oldLocalData = $env:LOCALAPPDATA
$oldLog = $env:AIENIE_ENTRY_TEST_LOG
$oldFailure = $env:AIENIE_ENTRY_TEST_FAIL
$stub = @'
[CmdletBinding()]
param([string]$Component,[string]$Service,[string]$Level,[string]$EnvironmentFile,[string]$EnvFile,
      [string]$AdminFile,[string]$StudioEnvironmentFile,[string]$StudioAdminFile,[string]$CapEnvironmentFile,
      [string]$TrustCertificatePath,[string]$PythonVenvRoot,[int]$StartupTimeoutSeconds,[int]$DebugPort,
      [switch]$NoBrowser,[switch]$EnableBackendDebug,[switch]$NoTrustCertificate,[string]$PreserveProcessIds)
$values=@{}
foreach($entry in $PSBoundParameters.GetEnumerator()) {
    $values[$entry.Key]=if($entry.Value -is [switch]){[bool]$entry.Value}else{$entry.Value}
}
$name=Split-Path $PSCommandPath -Leaf
@{script=$name;parameters=$values}|ConvertTo-Json -Compress -Depth 5|Add-Content $env:AIENIE_ENTRY_TEST_LOG
if($env:AIENIE_ENTRY_TEST_FAIL -eq $name){exit 7}
'@
function Invoke-Entry([string[]]$Arguments) {
    Clear-Content $env:AIENIE_ENTRY_TEST_LOG
    Push-Location ([IO.Path]::GetTempPath())
    try { & pwsh -NoProfile -File (Join-Path $fixture 'start.ps1') @Arguments *> $null; $script:entryExit=$LASTEXITCODE }
    finally { Pop-Location }
    $script:calls=@(Get-Content $env:AIENIE_ENTRY_TEST_LOG | ForEach-Object { $_ | ConvertFrom-Json })
}
function Assert([bool]$Condition,[string]$Message) { if(-not $Condition){throw $Message} }
try {
    $windows=Join-Path $fixture 'scripts/windows'
    New-Item -ItemType Directory -Path $windows -Force | Out-Null
    Copy-Item (Join-Path $repoRoot 'start.ps1') (Join-Path $fixture 'start.ps1')
    $fixtureEntry=Join-Path $fixture 'start.ps1'
    $entryText=Get-Content $fixtureEntry -Raw
    Set-Content $fixtureEntry $entryText.Replace('.workflow',('.workflow.'+(Split-Path $fixture -Leaf)))
    Copy-Item (Join-Path $repoRoot 'scripts/windows/LocalCommand.ps1') (Join-Path $windows 'LocalCommand.ps1')
    Add-Content (Join-Path $windows 'LocalCommand.ps1') "`nfunction Assert-LocalEndpoint { param([string]`$Uri,[switch]`$Health,[switch]`$Json) }"
    foreach($name in @('Build-Local','Test-Local','Start-Local','Get-LocalStatus','Stop-Local')) {
        Set-Content (Join-Path $windows "$name.ps1") $stub
    }
    $env:LOCALAPPDATA=$fixture
    $env:AIENIE_ENTRY_TEST_LOG=Join-Path $fixture 'calls.jsonl'
    New-Item -ItemType File -Path $env:AIENIE_ENTRY_TEST_LOG | Out-Null
    $selector=if($project -eq 'fireflyChat'){'Service'}else{'Component'}
    $part=if($project -eq 'fireflyChat'){'Studio'}else{'Frontend'}
    $envParameter=if($project -eq 'pdfToWord'){'EnvFile'}else{'EnvironmentFile'}
    $envPath=Join-Path $fixture 'private config.env'
    $env:AIENIE_ENTRY_TEST_FAIL=''
    Invoke-Entry @('-NoBrowser','-EnableBackendDebug',"-$selector",$part,"-$envParameter",$envPath)
    Assert ($entryExit -eq 0) 'Default Start failed.'
    Assert (($calls.script -join ',') -eq 'Build-Local.ps1,Start-Local.ps1') 'Default Start must build before starting and must not run tests.'
    Assert ($calls[1].parameters.NoBrowser -and $calls[1].parameters.EnableBackendDebug) 'Switch forwarding failed.'
    Assert ($calls[1].parameters.$selector -eq $part -and $calls[1].parameters.$envParameter -eq $envPath) 'Component or spaced environment-path forwarding failed.'
    $env:AIENIE_ENTRY_TEST_FAIL='Build-Local.ps1'
    Invoke-Entry @('-NoBrowser')
    Assert ($entryExit -ne 0 -and $calls.Count -eq 1) 'A failed build must return nonzero and prevent startup.'
    $env:AIENIE_ENTRY_TEST_FAIL='Test-Local.ps1'
    Invoke-Entry @('-Action','Test','-Level','L3')
    Assert ($entryExit -ne 0 -and $calls.Count -eq 1) 'A failed test must prevent L3 startup.'
    $env:AIENIE_ENTRY_TEST_FAIL=''
    Invoke-Entry @('-Action','Test')
    Assert ($entryExit -eq 0 -and $calls[0].parameters.Level -eq 'L2') 'Test must default to L2.'
    Invoke-Entry @('-Action','Test','-Level','L3')
    Assert ($entryExit -eq 0) 'L3 dispatch failed.'
    if($project -in @('SAMTerminal','pdfToWord')) {
        Assert ($calls[0].parameters.Level -eq 'L3') 'L3 must be forwarded to the test orchestrator.'
    } else {
        Assert (($calls.script -join ',') -eq 'Test-Local.ps1,Start-Local.ps1,Stop-Local.ps1') 'L3 must test, start, and clean up.'
        Assert ($calls[0].parameters.Level -eq 'L2' -and $calls[1].parameters.NoBrowser) 'L3 prerequisites or NoBrowser forwarding failed.'
    }
    Invoke-Entry @('-Action','Status')
    Assert ($entryExit -eq 0 -and $calls.Count -eq 1 -and $calls[0].script -eq 'Get-LocalStatus.ps1') 'Status must not build or start services.'
    Invoke-Entry @('-Action','Stop',"-$selector",$part)
    Assert ($entryExit -eq 0 -and $calls[0].parameters.$selector -eq $part) 'Stop scope forwarding failed.'
    $tasks=Get-Content (Join-Path $repoRoot '.vscode/tasks.json') -Raw | ConvertFrom-Json
    $launch=Get-Content (Join-Path $repoRoot '.vscode/launch.json') -Raw | ConvertFrom-Json
    foreach($config in $launch.configurations) {
        if($config.preLaunchTask) { Assert ($config.preLaunchTask -in $tasks.tasks.label) "Missing task: $($config.preLaunchTask)" }
    }
    foreach($compound in $launch.compounds) {
        Assert ($compound.preLaunchTask -in $tasks.tasks.label) "Missing compound task: $($compound.preLaunchTask)"
        foreach($name in $compound.configurations) { Assert ($name -in $launch.configurations.name) "Missing debug configuration: $name" }
    }
    & (Join-Path $PSScriptRoot 'Test-LocalEndpoint.ps1')
    Write-Output "$project root-entry regression checks passed."
} finally {
    $env:LOCALAPPDATA=$oldLocalData
    $env:AIENIE_ENTRY_TEST_LOG=$oldLog
    $env:AIENIE_ENTRY_TEST_FAIL=$oldFailure
    $resolved=[IO.Path]::GetFullPath($fixture)
    $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if($resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf) -match '^aienie-entry-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
