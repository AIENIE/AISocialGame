[CmdletBinding()]
param([ValidateSet('All','Backend','Frontend')][string]$Component = 'All', [ValidatePattern('^(\d+(,\d+)*)?$')][string]$PreserveProcessIds='')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Stop-Local requires PowerShell 7 Core; transparently re-enter under pwsh so
# this script also works from Windows PowerShell 5.1.
if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7) {
    # Windows PowerShell 5.1 predates the Platform property and runs on Windows only.
    $platform = if ($PSVersionTable.ContainsKey('Platform')) { [string]$PSVersionTable.Platform } else { 'Win32NT' }
    if ($platform -cne 'Win32NT') {
        throw 'This entrypoint supports native Windows only.'
    }
    $pwsh = Get-Command -Name 'pwsh.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $pwsh) {
        throw 'PowerShell 7 (pwsh) is required. Install it from https://aka.ms/powershell and re-run this script.'
    }
    & $pwsh.Source -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $PSCommandPath
    exit $LASTEXITCODE
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$stateRoot = Join-Path $(if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { $env:TEMP }) 'Aienie\native-runs\aisocialgame'
$statePath = Join-Path $stateRoot 'processes.json'

function Test-RecordedProcess {
    param([Parameter(Mandatory)]$Record)

    $process = Get-Process -Id ([int]$Record.ProcessId) -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $false }
    $processPath = try { [string]$process.Path } catch { return $false }
    if (-not [string]::Equals($processPath, [string]$Record.ProcessPath, [StringComparison]::OrdinalIgnoreCase)) { return $false }
    try {
        $startedUtc = $process.StartTime.ToUniversalTime()
        $recordedUtc = [DateTime]::Parse([string]$Record.StartedUtc, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
        if ([Math]::Abs(($startedUtc - $recordedUtc).TotalSeconds) -gt 2) { return $false }
    } catch {
        return $false
    }
    return $true
}

$state = $null
if (Test-Path -LiteralPath $statePath -PathType Leaf) {
    try {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    } catch {
        throw "Cannot parse native process state '$statePath': $($_.Exception.Message)"
    }
}
if ($null -eq $state) {
    Write-Output 'No recorded AISocialGame native processes are active.'
    return
}
if (-not [string]::Equals([string]$state.projectRoot, $repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Native process state belongs to another checkout: $($state.projectRoot)"
}

$remaining = @()
foreach ($record in @($state.processes)) {
    if ([string]$record.ProcessId -in ($PreserveProcessIds -split ',')) { $remaining += $record; continue }
    if ($Component -ne 'All' -and $record.Name -ne $Component) { $remaining += $record; continue }
    if (Test-RecordedProcess -Record $record) {
        & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID ([string]$record.ProcessId) /T /F | Out-Null
        Write-Output "Stopped AISocialGame $($record.Name) process $($record.ProcessId)."
    } else {
        Write-Output "Removed stale AISocialGame $($record.Name) record (PID $($record.ProcessId))."
    }
}
if ($remaining.Count -eq 0) { Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue } else { $state.processes = $remaining; $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8NoBOM }

foreach ($port in @(11031, 11030)) {
    $listeners = @(
        [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
            Where-Object { $_.Port -eq $port }
    )
    if ($listeners.Count -gt 0) {
        Write-Warning "TCP port $port is still listening after stop; no matching process record remained. Inspect the owning process manually."
    }
}
