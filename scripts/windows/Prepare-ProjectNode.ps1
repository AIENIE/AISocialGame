[CmdletBinding()] param()
Set-StrictMode -Version Latest; $ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7){throw 'Requires Windows PowerShell 7.'}
. (Join-Path $PSScriptRoot 'ProjectNode.ps1')
$spec=Get-ProjectNodeSpec
New-Item -ItemType Directory -Path $spec.Base -Force | Out-Null
$name="node-v$($spec.Node)-win-x64.zip"; $url="https://nodejs.org/dist/v$($spec.Node)"
$checks=(Invoke-WebRequest "$url/SHASUMS256.txt" -TimeoutSec 60).Content
$line=@($checks -split '\r?\n' | Where-Object {$_ -match "^[a-f0-9]{64}\s+$([regex]::Escape($name))$"})
if($line.Count -ne 1){throw 'Official checksum unavailable.'}
$expected=($line[0] -split '\s+')[0]; $archive=Join-Path $spec.Base $name
if(-not(Test-Path $archive)){Invoke-WebRequest "$url/$name" -OutFile $archive -TimeoutSec 300}
Assert-ProjectPackageHash $archive $expected
if(-not(Test-Path $spec.Home)){Expand-Archive -LiteralPath $archive -DestinationPath $spec.Base}
if((& (Join-Path $spec.Home 'node.exe') --version) -cne "v$($spec.Node)"){throw 'Extracted Node version mismatch.'}
$previous=@{}
foreach($name in @('PATH','COREPACK_HOME','COREPACK_ENABLE_NETWORK','COREPACK_ENABLE_DOWNLOAD_PROMPT')){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $env:PATH="$($spec.Home);$env:PATH"; $env:COREPACK_HOME=$spec.Cache; $env:COREPACK_ENABLE_NETWORK='1'; $env:COREPACK_ENABLE_DOWNLOAD_PROMPT='0'
    & corepack.cmd install --global "pnpm@$($spec.Pnpm)"; if($LASTEXITCODE){throw 'pnpm preparation failed.'}
    & corepack.cmd enable --install-directory $spec.Home; if($LASTEXITCODE){throw 'Project shims failed.'}
} finally {foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
Invoke-WithProjectNode { Write-Host "Ready Node $($spec.Node), pnpm $($spec.Pnpm)" }
@{node=$spec.Node;pnpm=$spec.Pnpm;url="$url/node-v$($spec.Node)-win-x64.zip";sha256=$expected} | ConvertTo-Json | Set-Content (Join-Path $spec.Base 'node-package.json')
