[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path 'D:\project\aienie\aienie-runtime\private\app-secrets' 'aisocialgame.env'),
    [ValidateSet("RulesV2", "Diagnostics", "Closure")]
    [string]$Migration = "RulesV2",
    [switch]$Apply
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 on Windows is required.' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$sharedTarget = Resolve-SharedMySqlTarget $EnvironmentFile
$migrationFile = Join-Path $repoRoot $(if ($Migration -eq 'Closure') { 'backend/sql/20260922_milestone_closure.sql' } elseif ($Migration -eq 'Diagnostics') { 'backend/sql/20260919_ai_turn_diagnostics.sql' } else { 'backend/sql/20260912_game_realism_v2.sql' })
$helper = Join-Path $PSScriptRoot 'support/GameRealismLocalData.java'
$mysqlJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' |
    Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
if (-not $mysqlJar) { throw 'Build the backend first to resolve the MySQL JDBC driver.' }
$mode = if ($Apply) { 'migrate' } else { 'inspect' }
& java.exe --class-path $mysqlJar $helper $mode (Resolve-Path -LiteralPath $EnvironmentFile).Path $migrationFile $sharedTarget.jdbcUrl
if ($LASTEXITCODE -ne 0) { throw "Local v2 migration failed with exit code $LASTEXITCODE." }
