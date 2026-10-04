[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path 'D:\project\aienie\aienie-runtime\private\app-secrets' 'aisocialgame.env'),
    [ValidateRange(30, 600)][int]$StartupTimeoutSeconds = 240,
    [ValidateSet('All','Backend','Frontend')][string]$Component = 'All',
    [switch]$EnableBackendDebug,
    [switch]$IsolatedAcceptance,
    [ValidatePattern("^[a-z0-9-]{1,40}$")][string]$InstanceName = "default",
    [ValidateRange(1024,65535)][int]$BackendPort = 11031,
    [ValidateRange(1024,65535)][int]$FrontendPort = 11030,
    # -NoBrowser skips opening the project homepage after a successful start.
    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalCommand.ps1')

# The foreground debug scripts require PowerShell 7 Core; transparently
# re-enter under pwsh so this script also works from Windows PowerShell 5.1.
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
    $forward = @()
    foreach ($entry in $PSBoundParameters.GetEnumerator()) {
        if ($entry.Value -is [System.Management.Automation.SwitchParameter]) {
            if ($entry.Value.IsPresent) { $forward += "-$($entry.Key)" }
        } else {
            $forward += "-$($entry.Key)"
            $forward += [string]$entry.Value
        }
    }
    & $pwsh.Source -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $PSCommandPath @forward
    exit $LASTEXITCODE
}

if (($IsolatedAcceptance -or $BackendPort -ne 11031 -or $FrontendPort -ne 11030) -and $InstanceName -eq 'default') { throw 'Isolated acceptance and custom ports require a named instance.' }
if ($BackendPort -eq $FrontendPort) { throw 'Backend and frontend ports must differ.' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

# The existing Start-Backend.ps1 / Start-Frontend.ps1 scripts own all env
# handling and foreground execution; this launcher hosts them as managed
# hidden background processes, waits for health, and records process
# identities for Stop-Local.ps1.
$components = @(
    [pscustomobject]@{ Name = 'Backend'; Script = (Join-Path $PSScriptRoot 'Start-Backend.ps1'); Port = $BackendPort; HealthPath = '/actuator/health'; HealthKind = 'JsonUp' },
    [pscustomobject]@{ Name = 'Frontend'; Script = (Join-Path $PSScriptRoot 'Start-Frontend.ps1'); Port = $FrontendPort; HealthPath = '/'; HealthKind = 'Http200' }
)
if ($Component -ne 'All') { $components = @($components | Where-Object Name -eq $Component) }
$stateRoot = Join-Path 'D:\project\aienie\aienie-runtime\local-services\direct-runs\native-runs' 'aisocialgame'
if ($InstanceName -ne 'default') { $stateRoot = Join-Path $stateRoot $InstanceName }
$statePath = Join-Path $stateRoot 'processes.json'
$logsRoot = Join-Path $stateRoot 'logs'

function Test-TcpPort {
    param([Parameter(Mandatory)][int]$Port)

    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(1000) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Test-HttpEndpoint {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('Http200', 'JsonUp')][string]$HealthKind = 'Http200'
    )

    try {
        $client = [Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds(3)
        try {
            $response = $client.GetAsync(("http://127.0.0.1:{0}{1}" -f $Port, $Path)).GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) { return $false }
            if ($HealthKind -ne 'JsonUp') { return $true }
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return $body -match '"status"\s*:\s*"UP"'
        } finally {
            $client.Dispose()
        }
    } catch {
        return $false
    }
}

function Get-State {
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { return $null }
    try {
        return Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    } catch {
        throw "Cannot parse native process state '$statePath': $($_.Exception.Message)"
    }
}

function Save-State {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records)

    New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
    [pscustomobject]@{
        product = 'AISocialGame'
        projectRoot = $repoRoot
        updatedUtc = [DateTime]::UtcNow.ToString('o')
        processes = @($Records)
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8NoBOM
}

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

function Convert-LaunchArgument {
    param([Parameter(Mandatory)][string]$Value)

    if ($Value -match '\s') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

function Start-Component {
    param([Parameter(Mandatory)]$Spec)

    if (Test-TcpPort -Port $Spec.Port) {
        throw "Port $($Spec.Port) is already listening without a live AISocialGame $($Spec.Name) record. Stop the owning process first."
    }
    New-Item -ItemType Directory -Path $logsRoot -Force | Out-Null
    $argumentList = @('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', (Convert-LaunchArgument -Value $Spec.Script))
    if ($Spec.Name -eq 'Backend') {
        $argumentList += @('-EnvironmentFile', (Convert-LaunchArgument -Value $EnvironmentFile), '-BackendPort', [string]$BackendPort)
        if ($EnableBackendDebug) { $argumentList += '-EnableBackendDebug' }
        if ($IsolatedAcceptance) { $argumentList += '-IsolatedAcceptance' }
    }
    if ($Spec.Name -eq 'Frontend') { $argumentList += @('-FrontendPort', [string]$FrontendPort, '-BackendPort', [string]$BackendPort) }
    $launcher = (Get-Process -Id $PID).Path
    $process = Start-Process -FilePath $launcher -ArgumentList $argumentList -WorkingDirectory $repoRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logsRoot "$($Spec.Name.ToLowerInvariant()).stdout.log") `
        -RedirectStandardError (Join-Path $logsRoot "$($Spec.Name.ToLowerInvariant()).stderr.log") -PassThru
    $launchedStartUtc = try { $process.StartTime.ToUniversalTime() } catch { $null }
    $ready = $false
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        do {
            if ($process.HasExited) {
                throw "AISocialGame $($Spec.Name) process exited during startup with code $($process.ExitCode). See logs under $logsRoot."
            }
            if ((Test-TcpPort -Port $Spec.Port) -and (Test-HttpEndpoint -Port $Spec.Port -Path $Spec.HealthPath -HealthKind $Spec.HealthKind)) {
                $ready = $true
                return [pscustomobject]@{
                    Name = $Spec.Name
                    ProcessId = $process.Id
                    ProcessPath = $process.Path
                    StartedUtc = $process.StartTime.ToUniversalTime().ToString('o')
                    Ports = @($Spec.Port)
                }
            }
            Start-Sleep -Seconds 1
            $process.Refresh()
        } while ([DateTime]::UtcNow -lt $deadline)
        throw "AISocialGame $($Spec.Name) did not become healthy on port $($Spec.Port) within $StartupTimeoutSeconds seconds. See logs under $logsRoot."
    } finally {
        if (-not $ready) {
        $current = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
        if ($null -ne $current -and $null -ne $launchedStartUtc -and
            [Math]::Abs(($current.StartTime.ToUniversalTime() - $launchedStartUtc).TotalMilliseconds) -le 1000) {
            & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID ([string]$process.Id) /T /F 2>$null | Out-Null
        }
        $process.Dispose()
        }
    }
}

$state = Get-State
if ($null -ne $state -and -not [string]::Equals([string]$state.projectRoot, $repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Native process state belongs to another checkout: $($state.projectRoot)"
}
$liveRecords = @()
if ($null -ne $state) {
    foreach ($record in @($state.processes)) {
        if (Test-RecordedProcess -Record $record) {
            $liveRecords += $record
        } else {
            Write-Output "Removing stale AISocialGame $($record.Name) process record (PID $($record.ProcessId))."
        }
    }
}

$records = @($liveRecords)
$created = @()
$stackReady = $false
try {
foreach ($spec in $components) {
    $existing = @($liveRecords | Where-Object Name -eq $spec.Name)
    if ($existing.Count -gt 0) {
        if (-not (Test-HttpEndpoint -Port $spec.Port -Path $spec.HealthPath -HealthKind $spec.HealthKind)) { throw "Recorded AISocialGame $($spec.Name) instance is not healthy." }
        if ($EnableBackendDebug -and $spec.Name -eq 'Backend') { Assert-LocalDebugListener $BackendPort 51031 }
        Write-Output "AISocialGame $($spec.Name) already running (PID $($existing[0].ProcessId)); skipping start."
        continue
    }
    Write-Output "Starting AISocialGame $($spec.Name) on port $($spec.Port)..."
    $record = Start-Component -Spec $spec
    $created += $record
    $records += $record
    Save-State -Records $records
}
Save-State -Records $records

if ($Component -in @('All','Frontend')) { Assert-LocalEndpoint 'https://localsocialgame.testhut.top/' }
$stackReady = $true
} finally {
    if (-not $stackReady) {
    foreach ($record in $created) {
        if (Test-RecordedProcess $record) { & (Join-Path $env:SystemRoot 'System32/taskkill.exe') /PID ([string]$record.ProcessId) /T /F | Out-Null }
    }
    Save-State -Records $liveRecords
    }
}

Write-Output ''
Write-Output 'AISocialGame local stack is up:'
Write-Output "  Backend : http://127.0.0.1:$BackendPort/actuator/health"
Write-Output "  Frontend: http://127.0.0.1:$FrontendPort/"
Write-Output '  Domain  : https://localsocialgame.testhut.top/ (requires the Windows nginx ingress)'
Write-Output 'Stop    : .\scripts\windows\Stop-Local.ps1'

if (-not $NoBrowser) {
    $homepage = if ($InstanceName -eq 'default') { 'https://localsocialgame.testhut.top/' } else { "http://127.0.0.1:$FrontendPort/" }
    Write-Output "Opening the project homepage in the default browser: $homepage"
    Start-Process -FilePath $homepage
}
