[CmdletBinding()]
param(
    [ValidateSet('Start','Build','Test','Status','Stop')][string]$Action = 'Start',
    [ValidateSet('L1','L2','L3')][string]$Level = 'L2',
    [ValidateSet('All','Backend','Frontend')][string]$Component = 'All',
    [string]$EnvironmentFile = (Join-Path 'D:\project\aienie\aienie-runtime\private\app-secrets' 'aisocialgame.env'),
    [switch]$NoBrowser,
    [switch]$EnableBackendDebug,
    [ValidateRange(30,900)][int]$StartupTimeoutSeconds = 240
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$windows = Join-Path $PSScriptRoot 'scripts/windows'
. (Join-Path $windows 'LocalCommand.ps1')
$selection = @{ Component = $Component }
$runtime = $selection.Clone()
foreach ($name in @('EnvironmentFile','StartupTimeoutSeconds')) {
    $runtime[$name] = Get-Variable -Name $name -ValueOnly
}
$operationMutex = [Threading.Mutex]::new($false, 'Local\Aienie.AISocialGame.workflow')
$ownsOperation = $false
try {
    if ($Action -ne 'Status') {
        try { $ownsOperation = $operationMutex.WaitOne(0) }
        catch [Threading.AbandonedMutexException] { $ownsOperation = $true }
        if (-not $ownsOperation) { throw 'Another build, test, start, or stop is already running for this project. Wait for it to finish.' }
    }
switch ($Action) {
    'Build' { Invoke-LocalScript (Join-Path $windows 'Build-Local.ps1') $selection }
    'Status' { Invoke-LocalScript (Join-Path $windows 'Get-LocalStatus.ps1') }
    'Stop' { Invoke-LocalScript (Join-Path $windows 'Stop-Local.ps1') $selection }
    'Start' {
        Invoke-LocalScript (Join-Path $windows 'Build-Local.ps1') $selection
        $runtime.NoBrowser = $NoBrowser
        $runtime.EnableBackendDebug = $EnableBackendDebug
        Invoke-LocalScript (Join-Path $windows 'Start-Local.ps1') $runtime
    }
    'Test' {
        $test = $selection.Clone()
        $test.Level = if ($Level -eq 'L3') { 'L2' } else { $Level }
        Invoke-LocalScript (Join-Path $windows 'Test-Local.ps1') $test
        if ($Level -ne 'L3') { break }
        $stateFile = Join-Path 'D:\project\aienie\aienie-runtime\local-services\direct-runs\native-runs' 'aisocialgame/processes.json'
        $preservedIds = @(Get-LocalRecordedIds $stateFile) -join ','
        try {
            $runtime.NoBrowser = $true
            Invoke-LocalScript (Join-Path $windows 'Start-Local.ps1') $runtime
            if ($Component -in @('All','Backend')) { Assert-LocalEndpoint 'http://127.0.0.1:11031/actuator/health' -Health }
            if ($Component -in @('All','Frontend')) {
                Assert-LocalEndpoint 'http://127.0.0.1:11030/'
                Assert-LocalEndpoint 'https://localsocialgame.testhut.top/'
            }
            if ($Component -eq 'All') { Assert-LocalEndpoint 'https://localsocialgame.testhut.top/api/admin/auth/policy' -Json }
        } finally {
            $stop = $selection.Clone()
            $stop.PreserveProcessIds = $preservedIds
            Invoke-LocalScript (Join-Path $windows 'Stop-Local.ps1') $stop
        }
    }
}

} finally {
    if ($ownsOperation) { $operationMutex.ReleaseMutex() }
    $operationMutex.Dispose()
}
