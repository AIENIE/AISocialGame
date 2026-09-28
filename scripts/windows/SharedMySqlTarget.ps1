function Get-AienieWorkspaceRoot {
    $cursor=Get-Item (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
    while($null -ne $cursor){
        if((Test-Path (Join-Path $cursor.FullName 'aienie-infra/infrastructure/catalog/environment-matrix.yaml')) -and (Test-Path (Join-Path $cursor.FullName 'aienie-doc/system-matrix'))){return $cursor.FullName}
        $cursor=$cursor.Parent
    }
    throw 'Canonical Aienie workspace is unavailable.'
}
function Resolve-SharedMySqlTarget {
    param([Parameter(Mandatory)][string]$EnvironmentFile)
    $workspace=Get-AienieWorkspaceRoot
    & (Join-Path $workspace 'aienie-infra/infrastructure/windows/Test-AienieSystemMatrix.ps1') `
        -OperationsDocumentPath (Join-Path $workspace 'aienie-doc/system-matrix/system-matrix-operations.md') `
        -IntegrationDocumentPath (Join-Path $workspace 'aienie-doc/system-matrix/system-matrix-integration.md') `
        -DiagramDirectoryPath (Join-Path $workspace 'aienie-doc/system-matrix/diagrams') | Out-Null
    $json=& python.exe (Join-Path $PSScriptRoot 'support/shared_target.py') (Join-Path $workspace 'aienie-infra/infrastructure/catalog/environment-matrix.yaml') $EnvironmentFile
    if($LASTEXITCODE -ne 0){throw 'UNKNOWN: shared database target resolver failed.'}
    $target=$json | ConvertFrom-Json
    if($target.status -cne 'PASS'){throw "UNKNOWN: shared database target not confirmed ($($target.reason)); no connection attempted."}
    return $target
}
