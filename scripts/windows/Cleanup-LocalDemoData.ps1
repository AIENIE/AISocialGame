[CmdletBinding()]
param(
    [ValidateSet('dry-run','apply')][string]$Mode = 'dry-run',
    [string]$EnvironmentFile = 'D:\project\aienie\aienie-runtime\private\app-secrets\aisocialgame.env'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 on Windows is required.' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$target = Resolve-SharedMySqlTarget $EnvironmentFile
$classPathFile = Join-Path $repoRoot 'backend/target/demo-cleanup-classpath.txt'
& mvn.cmd -q -f (Join-Path $repoRoot 'backend/pom.xml') dependency:build-classpath '-DincludeScope=test' "-Dmdep.outputFile=$classPathFile"
if ($LASTEXITCODE) { throw 'Cleanup dependencies unavailable.' }
$classPath = (Get-Content -LiteralPath $classPathFile -Raw).Trim()
& python.exe (Join-Path $PSScriptRoot 'support/run_demo_cleanup.py') $Mode $classPath (Join-Path $PSScriptRoot 'support/DemoDataCleanup.java') (Resolve-Path -LiteralPath $EnvironmentFile).Path (Join-Path $PSScriptRoot 'support/demo-seed-manifest.json') $target.jdbcUrl (Join-Path (Get-AienieWorkspaceRoot) 'aienie-infra/infrastructure/catalog/environment-matrix.yaml')
if ($LASTEXITCODE) { throw 'Cleanup failed; transaction rolled back.' }
