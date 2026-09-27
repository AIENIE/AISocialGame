[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$BudgetFile,
    [Parameter(Mandatory)][string]$ManifestFile,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [string]$AccountsFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or $PSVersionTable.PSVersion.Major -lt 7){throw 'PowerShell 7 on Windows is required.'}
. (Join-Path $PSScriptRoot 'SharedMySqlTarget.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workspace=Get-AienieWorkspaceRoot
$invalid=@()
try {
    & (Join-Path $workspace 'aienie-runtime/infrastructure/windows/Test-AienieSystemMatrix.ps1') `
        -OperationsDocumentPath (Join-Path $workspace 'aienie-doc/system-matrix/system-matrix-operations.md') `
        -IntegrationDocumentPath (Join-Path $workspace 'aienie-doc/system-matrix/system-matrix-integration.md') `
        -DiagramDirectoryPath (Join-Path $workspace 'aienie-doc/system-matrix/diagrams') | Out-Null
} catch {$invalid=@('--matrix-invalid')}
$jar=Get-ChildItem (Join-Path $root 'backend/target') -Filter '*.jar' | Where-Object Name -NotLike '*.original' | Select-Object -First 1
$options=@();if($AccountsFile){$options=@('--accounts',$AccountsFile)}
& python.exe (Join-Path $PSScriptRoot 'support/closure_preflight.py') --root $root `
    --matrix (Join-Path $workspace 'aienie-runtime/infrastructure/catalog/environment-matrix.yaml') `
    --environment $EnvironmentFile --ledger $BudgetFile --manifest $ManifestFile --jar $jar.FullName --output $EvidenceDirectory @options @invalid
if($LASTEXITCODE -ne 0){throw 'Read-only preflight failed. No execution authorization was produced.'}
