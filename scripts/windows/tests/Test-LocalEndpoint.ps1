[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\LocalCommand.ps1')
$script:mockResponse=[pscustomobject]@{StatusCode=200;Content=([Text.Encoding]::UTF8.GetBytes('{"status":"UP"}'))}
function Invoke-WebRequest { param([string]$Uri,[int]$TimeoutSec,[hashtable]$Headers) return $script:mockResponse }
Assert-LocalEndpoint 'https://mock/bytes' -Health
$script:mockResponse=[pscustomobject]@{StatusCode=200;Content='{"status":"UP"}'}
Assert-LocalEndpoint 'https://mock/string' -Health
$script:mockResponse=[pscustomobject]@{StatusCode=200;Content='{"status":"DOWN"}'}
$failed=$false;try{Assert-LocalEndpoint 'https://mock/down' -Health}catch{$failed=$true};if(-not $failed){throw 'DOWN health payload was accepted.'}
$script:mockResponse=[pscustomobject]@{StatusCode=200;Content='not-json'}
$failed=$false;try{Assert-LocalEndpoint 'https://mock/invalid' -Json}catch{$failed=$true};if(-not $failed){throw 'Invalid JSON payload was accepted.'}
$script:mockResponse=[pscustomobject]@{StatusCode=503;Content='{"status":"UP"}'}
$failed=$false;try{Assert-LocalEndpoint 'https://mock/status' -Json}catch{$failed=$true};if(-not $failed){throw 'Non-200 response was accepted.'}
$script:mockResponse=[pscustomobject]@{StatusCode=200;Content=([byte[]](0xff,0xfe,0xfd))}
$failed=$false;try{Assert-LocalEndpoint 'https://mock/utf8' -Json}catch{$failed=$true};if(-not $failed){throw 'Invalid UTF-8 JSON payload was accepted.'}
Write-Output 'Local endpoint mock checks passed (6 cases).'
