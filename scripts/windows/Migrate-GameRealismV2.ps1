[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path 'D:\project\aienie\aienie-runtime\private\app-secrets' 'aisocialgame.env'),
    [ValidateSet("RulesV2", "Diagnostics", "Closure", "Audit", "AiBudget")]
    [string]$Migration = "RulesV2",
    [switch]$Apply
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 on Windows is required.' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$sharedTarget = Resolve-SharedMySqlTarget $EnvironmentFile
. (Join-Path $repoRoot 'scripts/config-pair/ConfigurationPair.ps1')
$configuration = Read-ConfigPairValues -ProjectRoot $repoRoot -EnvironmentFile $EnvironmentFile
$databaseUser = $configuration['SPRING_DATASOURCE_USERNAME']
if ([string]::IsNullOrWhiteSpace($databaseUser)) { throw 'Database username is not configured.' }
$migrationFile = Join-Path $repoRoot $(if ($Migration -eq 'AiBudget') { 'backend/sql/20261002_ai_credit_escrow.sql' } elseif ($Migration -eq 'Closure') { 'backend/sql/20260922_milestone_closure.sql' } elseif ($Migration -eq 'Diagnostics') { 'backend/sql/20260919_ai_turn_diagnostics.sql' } else { 'backend/sql/20260912_game_realism_v2.sql' })
$migrationFiles = @($migrationFile)
if ($Migration -eq 'Audit') {
    $migrationFiles = @('20260926_audit_access.sql', '20260926_audit_settlement.sql',
        '20260927_audit_trace_instance.sql', '20260927_audit_public_event_cursor.sql') |
        ForEach-Object { Join-Path $repoRoot "backend/sql/$_" }
}
$helper = Join-Path $PSScriptRoot 'support/GameRealismLocalData.java'
$mysqlJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j') -Recurse -Filter '*.jar' |
    Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
if (-not $mysqlJar) { throw 'Build the backend first to resolve the MySQL JDBC driver.' }
$mode = if ($Apply) { 'migrate' } else { 'inspect' }
foreach ($file in $migrationFiles) {
    & java.exe --class-path $mysqlJar $helper $mode (Resolve-Path -LiteralPath $EnvironmentFile).Path $file $sharedTarget.jdbcUrl $databaseUser
    if ($LASTEXITCODE -ne 0) { throw "Local migration failed for $([IO.Path]::GetFileName($file)) with exit code $LASTEXITCODE." }
}
