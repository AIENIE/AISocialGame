[CmdletBinding()] param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../ProjectNode.ps1')
$actual=Get-ProjectNodeSpec
Invoke-WithProjectNode {$script:sourceNode=(Get-Command node.exe).Source}
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('project node test '+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$pathBefore=$env:PATH; $cacheBefore=$env:COREPACK_HOME
$overrideBefore=$env:CODEX_MCP_NODE_PATH
function Assert([bool]$Value,[string]$Message){if(-not $Value){throw $Message}}
function MustFail([scriptblock]$Action){$failed=$false;try{& $Action}catch{$failed=$true};Assert $failed 'Expected rejection'}
try{
    Copy-Item $script:sourceNode (Join-Path $fixture 'node.exe')
    "@echo $($actual.Pnpm)" | Set-Content (Join-Path $fixture 'corepack.cmd')
    $script:fixtureSpec=[pscustomobject]@{Node=$actual.Node;Pnpm=$actual.Pnpm;Home=$fixture;Cache=(Join-Path $fixture 'cache')}
    function Get-ProjectNodeSpec{return $script:fixtureSpec}
    $env:CODEX_MCP_NODE_PATH='C:\untrusted alternate runtime\node.exe'
    Invoke-WithProjectNode {Assert ((Get-VerifiedProjectNode) -ceq (Join-Path $fixture 'node.exe')) 'Editor override bypassed project Node'}
    Invoke-WithProjectNode {Assert ((& node.exe --version) -ceq "v$($actual.Node)") 'Wrong resolved Node';Assert ($env:COREPACK_ENABLE_NETWORK -eq '0') 'Implicit tool download allowed'}
    Assert ($env:PATH -ceq $pathBefore) 'PATH leaked';Assert ([string]$env:COREPACK_HOME -ceq [string]$cacheBefore) 'Cache environment leaked'
    MustFail {Invoke-WithProjectNode {throw 'Action failed'}}
    Assert ($env:PATH -ceq $pathBefore) 'Failure leaked PATH'
    $script:executed=$false
    $script:fixtureSpec.Node='0.0.0';MustFail {Invoke-WithProjectNode {$script:executed=$true}};$script:fixtureSpec.Node=$actual.Node
    Assert (-not $script:executed) 'Wrong Node reached the action'
    '@echo 0.0.0' | Set-Content (Join-Path $fixture 'corepack.cmd');MustFail {Invoke-WithProjectNode {$script:executed=$true}}
    Assert (-not $script:executed) 'Wrong pnpm reached the action'
    $file=Join-Path $fixture 'package.zip';'fixture' | Set-Content $file
    Assert-ProjectPackageHash $file (Get-FileHash $file -Algorithm SHA256).Hash
    MustFail {Assert-ProjectPackageHash $file ('0'*64)}
    Write-Host 'PASS Node exact versions, spaces, checksum and environment restoration (7 checks).'
}finally{
    [Environment]::SetEnvironmentVariable('CODEX_MCP_NODE_PATH',$overrideBefore,'Process')
    $resolved=[IO.Path]::GetFullPath($fixture);$tmp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if(-not $resolved.StartsWith($tmp,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'project node test *'){throw 'Unsafe cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
