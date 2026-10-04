[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$FrontendPort = 11030, [ValidateRange(1024,65535)][int]$BackendPort = 11031)

. (Join-Path $PSScriptRoot 'ProjectNode.ps1')
Invoke-WithProjectNode {
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7 -or $env:OS -ne 'Windows_NT') {
    throw 'Start-Frontend.ps1 requires PowerShell 7 or newer on Windows.'
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$frontendDirectory = Join-Path $repoRoot 'frontend'

function Test-PortListening([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try { $task = $client.ConnectAsync('127.0.0.1', $Port); return $task.Wait(1000) -and $client.Connected } catch { return $false } finally { $client.Dispose() }
}

$corepack = (Get-Command -Name 'corepack.cmd' -CommandType Application -ErrorAction Stop | Select-Object -First 1).Path

if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules') -PathType Container)) {
    Write-Host 'frontend dependencies are missing; running pnpm install --frozen-lockfile ...'
    & $corepack "pnpm@$($spec.Pnpm)" --dir $frontendDirectory install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
}

if (Test-PortListening $FrontendPort) {
    throw 'Port 11030 is already listening. Stop the owning process before starting the debug frontend.'
}

$env:VITE_LOCAL_BACKEND_PORT = [string]$BackendPort

Write-Host "Starting AISocialGame frontend (debug) on http://127.0.0.1:$FrontendPort - press Ctrl+C to stop."
& $corepack "pnpm@$($spec.Pnpm)" --dir $frontendDirectory exec vite --host 127.0.0.1 --port $FrontendPort --strictPort
$exitCode = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } else { 0 }
if ($exitCode -ne 0) { Write-Host "Frontend exited with code $exitCode." }
exit $exitCode

}
