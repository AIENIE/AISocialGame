[CmdletBinding()] param([ValidateSet('All','Backend','Frontend')][string]$Component='All',[ValidateSet('L1','L2','L3')][string]$Level='L2')
Set-StrictMode -Version Latest; $ErrorActionPreference='Stop'
if($Component -in @('All','Backend')){
 $buildRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
 & python.exe (Join-Path $PSScriptRoot 'support/closure_identity.py') prepare $buildRoot | Out-Null; if($LASTEXITCODE){exit $LASTEXITCODE}
 & mvn.cmd -q -f (Join-Path $PSScriptRoot '..\..\backend\pom.xml') -DskipTests package; if($LASTEXITCODE){exit $LASTEXITCODE}
 $buildJar=Get-ChildItem (Join-Path $buildRoot 'backend/target') -Filter '*.jar' | Where-Object Name -NotLike '*.original' | Select-Object -First 1
 & python.exe (Join-Path $PSScriptRoot 'support/closure_identity.py') artifact $buildRoot --jar $buildJar.FullName | Out-Null; if($LASTEXITCODE){exit $LASTEXITCODE}; if($LASTEXITCODE){exit $LASTEXITCODE}
 }
if($Component -in @('All','Frontend')){
 . (Join-Path $PSScriptRoot 'ProjectNode.ps1')
 Invoke-WithProjectNode {
  $f=Join-Path $PSScriptRoot '../../frontend'
  & corepack.cmd "pnpm@$($spec.Pnpm)" --dir $f install --frozen-lockfile; if($LASTEXITCODE){throw 'Dependency install failed.'}
  & corepack.cmd "pnpm@$($spec.Pnpm)" --dir $f run lint; if($LASTEXITCODE){throw 'Lint failed.'}
  & corepack.cmd "pnpm@$($spec.Pnpm)" --dir $f run typecheck; if($LASTEXITCODE){throw 'Type check failed.'}
  & corepack.cmd "pnpm@$($spec.Pnpm)" --dir $f run test:typecheck; if($LASTEXITCODE){throw 'Type check negative fixtures failed.'}
  & corepack.cmd "pnpm@$($spec.Pnpm)" --dir $f run build; if($LASTEXITCODE){throw 'Frontend build failed.'}
 }
}
