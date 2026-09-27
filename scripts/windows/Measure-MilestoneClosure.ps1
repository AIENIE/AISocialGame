[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$Evidence,[Parameter(Mandatory)][string]$Review,[string]$Games,[string]$Runtime)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 on Windows is required.' }
$closureArgs=@()
if($Games){$closureArgs+=@("--games",$Games)}
if($Runtime){$closureArgs+=@("--runtime",$Runtime)}
& python.exe (Join-Path $PSScriptRoot 'support/closure_metrics.py') --manifest $Manifest --evidence $Evidence --review $Review @closureArgs
if ($LASTEXITCODE -ne 0) { throw 'Evaluation report validation failed.' }
