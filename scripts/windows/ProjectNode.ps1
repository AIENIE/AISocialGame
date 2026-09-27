function Assert-ProjectPackageHash {
    param([string]$Path,[string]$Expected,[ValidateSet('SHA256','MD5')][string]$Algorithm='SHA256')
    if(-not $Expected -or (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ine $Expected){throw 'Official package checksum mismatch.'}
}
function Get-ProjectNodeSpec {
    $root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
    $package=Get-Content (Join-Path $root 'frontend/package.json') -Raw | ConvertFrom-Json
    if($package.engines.node -notmatch '^\d+\.\d+\.\d+$' -or $package.packageManager -cne "pnpm@$($package.engines.pnpm)"){throw 'Exact consistent Node/pnpm versions are required.'}
    $base=Join-Path 'D:\project\aienie\aienie-runtime\windows\toolchains\localappdata' 'aisocialgame'
    [pscustomobject]@{Node=$package.engines.node;Pnpm=$package.engines.pnpm;Base=$base;Home=(Join-Path $base "node-v$($package.engines.node)-win-x64");Cache=(Join-Path $base 'corepack')}
}
function Invoke-WithProjectNode {
    param([Parameter(Mandatory)][scriptblock]$Action)
    $spec=Get-ProjectNodeSpec
    $homePath=$spec.Home
    if(-not(Test-Path (Join-Path $homePath 'node.exe'))){
        $system=Get-Command node.exe -ErrorAction SilentlyContinue
        if($system -and (& $system.Source --version) -ceq "v$($spec.Node)"){$homePath=Split-Path $system.Source}
        else{throw 'Project Node is missing. Run scripts/windows/Prepare-ProjectNode.ps1.'}
    }
    $previous=@{}
    foreach($name in @('PATH','COREPACK_HOME','COREPACK_ENABLE_NETWORK','COREPACK_ENABLE_DOWNLOAD_PROMPT')){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try {
        $env:PATH="$homePath;$env:PATH"; $env:COREPACK_HOME=$spec.Cache; $env:COREPACK_ENABLE_NETWORK='0'; $env:COREPACK_ENABLE_DOWNLOAD_PROMPT='0'
        if((& node.exe --version) -cne "v$($spec.Node)"){throw 'Wrong Node version.'}
        $pnpm=& corepack.cmd "pnpm@$($spec.Pnpm)" --version
        if($LASTEXITCODE -ne 0 -or $pnpm -cne $spec.Pnpm){throw 'Project pnpm is missing or wrong. Run scripts/windows/Prepare-ProjectNode.ps1.'}
        & $Action
    } finally {foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
}
function Get-VerifiedProjectNode {
    # Resolve the child executable after Invoke-WithProjectNode has scoped PATH.
    # Editor-specific runtime variables never select an acceptance runtime.
    $spec=Get-ProjectNodeSpec
    $executable=(Get-Command node.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $version=& $executable --version
    if($LASTEXITCODE -ne 0 -or $version -cne "v$($spec.Node)"){throw 'Acceptance Node version mismatch.'}
    return $executable
}
