[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$EvidenceFile,
    [Parameter(Mandatory)][string]$OutputFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7 -or
        [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Local metrics require PowerShell 7 or newer on Windows.'
}
$metricsRepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$sharedTarget = Resolve-SharedMySqlTarget $EnvironmentFile
$metricsHelper = Join-Path $PSScriptRoot 'support/GameRealismMetrics.java'
$metricsMavenRepository = Join-Path $env:USERPROFILE '.m2/repository'

function Find-MetricsDependency([string]$RelativeDirectory, [string]$Artifact) {
    $directory = Join-Path $metricsMavenRepository $RelativeDirectory
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Existing Maven dependency is missing: $Artifact. Build the backend first." }
    $candidates = foreach ($versionDirectory in Get-ChildItem -LiteralPath $directory -Directory) {
        $version = $null
        if ([Version]::TryParse($versionDirectory.Name, [ref]$version)) {
            $jar = Join-Path $versionDirectory.FullName "$Artifact-$($versionDirectory.Name).jar"
            if (Test-Path -LiteralPath $jar -PathType Leaf) { [pscustomobject]@{ Version = $version; Path = $jar } }
        }
    }
    $selected = $candidates | Sort-Object Version -Descending | Select-Object -First 1
    if (-not $selected) { throw "Existing Maven dependency is missing: $Artifact. Build the backend first." }
    return $selected.Path
}

$metricsJars = @(
    (Find-MetricsDependency 'com/mysql/mysql-connector-j' 'mysql-connector-j'),
    (Find-MetricsDependency 'com/fasterxml/jackson/core/jackson-databind' 'jackson-databind'),
    (Find-MetricsDependency 'com/fasterxml/jackson/core/jackson-core' 'jackson-core'),
    (Find-MetricsDependency 'com/fasterxml/jackson/core/jackson-annotations' 'jackson-annotations')
)
$metricsStart = [Diagnostics.ProcessStartInfo]::new()
$metricsStart.FileName = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$metricsStart.UseShellExecute = $false
$metricsStart.CreateNoWindow = $true
$metricsStart.RedirectStandardOutput = $true
$metricsStart.RedirectStandardError = $true
foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) { [void]$metricsStart.Environment.Remove($name) }
foreach ($argument in @('--class-path', ($metricsJars -join [IO.Path]::PathSeparator), $metricsHelper,
        (Resolve-Path -LiteralPath $EnvironmentFile).Path, (Resolve-Path -LiteralPath $EvidenceFile).Path,
        [IO.Path]::GetFullPath($OutputFile), $metricsRepoRoot, $sharedTarget.jdbcUrl)) { $metricsStart.ArgumentList.Add($argument) }
$metricsProcess = [Diagnostics.Process]::new()
$metricsProcess.StartInfo = $metricsStart
try {
    if (-not $metricsProcess.Start()) { throw 'Local metrics helper did not start.' }
    $metricsStdout = $metricsProcess.StandardOutput.ReadToEndAsync()
    $metricsStderr = $metricsProcess.StandardError.ReadToEndAsync()
    $metricsProcess.WaitForExit()
    $metricsOut = $metricsStdout.GetAwaiter().GetResult()
    $metricsErr = $metricsStderr.GetAwaiter().GetResult()
    # The helper emits fixed status lines only. Never echo a driver exception or private file content.
    if ($metricsProcess.ExitCode -ne 0) {
        $status = ([regex]::Matches($metricsErr, '(?m)^METRICS_FAILED [A-Z_]+(?: SQLSTATE=[A-Z0-9]+ CODE=-?[0-9]+)?\r?$') | ForEach-Object { $_.Value.TrimEnd("`r") }) -join ' '
        if (-not $status) { $status = 'METRICS_FAILED HELPER_ERROR' }
        throw "$status; helper exit code $($metricsProcess.ExitCode)."
    }
    if ($metricsOut -notmatch '(?m)^METRICS_WRITTEN\r?$') { throw 'Local metrics helper returned no completion marker.' }
    Write-Host 'Local read-only metrics written to OutputFile.'
} finally { $metricsProcess.Dispose() }
