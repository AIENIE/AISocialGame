[CmdletBinding()]
param(
    [ValidateSet('dry-run','apply')][string]$Mode='dry-run',
    [Parameter(Mandatory)][string]$PlanFile,
    [string]$EnvironmentFile=$(Join-Path $(if ($env:AIENIE_RUNTIME_ROOT) { $env:AIENIE_RUNTIME_ROOT } else { Join-Path $PSScriptRoot '../../../../aienie-runtime' }) 'private/app-secrets/aisocialgame.env')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if ($PSVersionTable.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 on Windows is required.' }
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$target=Resolve-SharedMySqlTarget $EnvironmentFile
if ($Mode -eq 'apply' -and (Get-NetTCPConnection -State Listen -LocalPort 11031 -ErrorAction SilentlyContinue)) { throw 'Stop the local backend before applying history cleanup.' }
$plan=[IO.Path]::GetFullPath($PlanFile)
$classPathFile=Join-Path $repo 'backend/target/history-cleanup-classpath.txt'
& mvn.cmd -q -f (Join-Path $repo 'backend/pom.xml') -DskipTests compile dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classPathFile"
if ($LASTEXITCODE) { throw 'Cleanup classes or dependencies unavailable.' }
$classPath=(Join-Path $repo 'backend/target/classes')+[IO.Path]::PathSeparator+(Get-Content -LiteralPath $classPathFile -Raw).Trim()
& python.exe (Join-Path $PSScriptRoot 'support/run_history_cleanup.py') $Mode $classPath $plan (Resolve-Path -LiteralPath $EnvironmentFile).Path $target.jdbcUrl (Join-Path (Get-AienieWorkspaceRoot) 'aienie-infra/infrastructure/catalog/environment-matrix.yaml') (Join-Path $repo 'backend/sql/20261029_retire_credit_holds.sql')
if ($LASTEXITCODE) { throw 'Local history cleanup failed; inspect the preserved plan and result before retrying.' }
