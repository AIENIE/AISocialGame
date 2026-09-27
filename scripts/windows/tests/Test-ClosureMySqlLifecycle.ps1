[CmdletBinding()] param([Parameter(Mandatory)][string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$runner=Join-Path $PSScriptRoot '../Test-ClosureMySql.ps1'
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start();$port=([Net.IPEndPoint]$listener.LocalEndpoint).Port
try {
    $rejected=$false
    try { & $runner -Port $port -EvidenceDirectory (Join-Path $EvidenceDirectory 'occupied') } catch {$rejected=$true}
    if(-not $rejected){throw 'Occupied port was accepted'}
    if(Test-Path (Join-Path $EvidenceDirectory 'occupied')){throw 'Occupied port created a runtime'}
}finally{$listener.Stop()}
$failure=Join-Path $EvidenceDirectory 'intentional-failure'
$rejected=$false
try{& $runner -EvidenceDirectory $failure -VerifyFailureCleanup}catch{$rejected=$true}
if(-not $rejected){throw 'Expected intentional failure'}
$result=Get-Content (Join-Path $failure 'result.json') -Raw | ConvertFrom-Json
if($result.passed -or $result.listenerRemaining){throw 'Failure cleanup did not stop the isolated database'}
Write-Host 'PASS occupied port rejection and live failure cleanup.'
