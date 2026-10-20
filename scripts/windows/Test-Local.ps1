[CmdletBinding()] param([ValidateSet('All','Backend','Frontend')][string]$Component='All',[ValidateSet('L1','L2','L3')][string]$Level='L2')
. (Join-Path $PSScriptRoot 'LocalCommand.ps1')
if ($Level -eq 'L3') { Invoke-LocalScript (Join-Path $PSScriptRoot '../../start.ps1') @{Action='Test';Level='L3';Component=$Component}; exit 0 }
if ($Level -ne 'L1') { Invoke-LocalScript (Join-Path $PSScriptRoot 'tests/Test-RootEntry.ps1'); Invoke-LocalScript (Join-Path $PSScriptRoot 'tests/Test-AdminEmergencyAcceptance.ps1') }

Set-StrictMode -Version Latest; $ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Build-Local.ps1') -Component $Component
if($LASTEXITCODE){exit $LASTEXITCODE}
if($Level -in @('L2','L3')){
 if($Component -in @('All','Backend')){ & mvn.cmd -q -f (Join-Path $PSScriptRoot '..\..\backend\pom.xml') test; if($LASTEXITCODE){exit $LASTEXITCODE} }
 if($Component -in @('All','Frontend')){
  Invoke-LocalScript (Join-Path $PSScriptRoot 'tests/Test-ProjectNode.ps1')
  . (Join-Path $PSScriptRoot 'ProjectNode.ps1')
  Invoke-WithProjectNode {
   & corepack.cmd "pnpm@$($spec.Pnpm)" --dir (Join-Path $PSScriptRoot '../../frontend') run test:unit -- --pool=threads --maxWorkers=1
   if($LASTEXITCODE){throw 'Frontend tests failed.'}
  }
 }
}
