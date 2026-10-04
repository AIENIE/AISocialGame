$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../AdminEmergencyAcceptance.ps1')
$values=@{AIENIE_ADMIN_EMERGENCY_ACCEPTANCE='true';ENV='local';AUTH_MODE='totp';AIENIE_RUNTIME_PLANE='windows-local';SERVER_ADDRESS='127.0.0.1';SERVER_PORT='12031';SPRING_PROFILES_ACTIVE='local'}
$yaml=@{AIENIE_ADMIN_EMERGENCY_ACCEPTANCE='true'}
$target=[pscustomobject]@{status='PASS';database='aienie_emergency_20261004_social';configuredTarget=[pscustomobject]@{host='localmysql.testhut.top';port=23306};matrixTarget=[pscustomobject]@{host='localmysql.testhut.top';port=23306}}
Assert-AdminEmergencyAcceptance -Values $values -YamlValues $yaml -SharedTarget $target -BackendPort 12031 -IsolatedAcceptance
function Assert-Rejected([scriptblock]$Action) { $rejected=$false;try { & $Action } catch { $rejected=$true };if(-not $rejected){throw 'Expected invalid acceptance configuration to be rejected.'} }
foreach($entry in @(@{Key='ENV';Value='test'},@{Key='ENV';Value='production'},@{Key='AUTH_MODE';Value='password'},@{Key='AIENIE_RUNTIME_PLANE';Value='linux'},@{Key='SERVER_ADDRESS';Value='0.0.0.0'},@{Key='SERVER_PORT';Value='11031'},@{Key='SPRING_PROFILES_ACTIVE';Value='test'})) {
 $changed=$values.Clone();$changed[$entry.Key]=$entry.Value
 Assert-Rejected { Assert-AdminEmergencyAcceptance -Values $changed -YamlValues $yaml -SharedTarget $target -BackendPort 12031 -IsolatedAcceptance }
}
Assert-Rejected { Assert-AdminEmergencyAcceptance -Values $values -YamlValues @{} -SharedTarget $target -BackendPort 12031 -IsolatedAcceptance }
Assert-Rejected { Assert-AdminEmergencyAcceptance -Values $values -YamlValues $yaml -SharedTarget $target -BackendPort 12032 -IsolatedAcceptance }
Assert-Rejected { Assert-AdminEmergencyAcceptance -Values $values -YamlValues $yaml -SharedTarget $target -BackendPort 12031 }
$bad=[pscustomobject]@{status='PASS';database='aienie_emergency_20261004_pdf';configuredTarget=$target.configuredTarget;matrixTarget=$target.matrixTarget}
Assert-Rejected { Assert-AdminEmergencyAcceptance -Values $values -YamlValues $yaml -SharedTarget $bad -BackendPort 12031 -IsolatedAcceptance }
$bad=[pscustomobject]@{status='PASS';database=$target.database;configuredTarget=[pscustomobject]@{host='other.testhut.top';port=23306};matrixTarget=$target.matrixTarget}
Assert-Rejected { Assert-AdminEmergencyAcceptance -Values $values -YamlValues $yaml -SharedTarget $bad -BackendPort 12031 -IsolatedAcceptance }
Assert-AdminEmergencyAcceptance -Values @{AIENIE_ADMIN_EMERGENCY_ACCEPTANCE='false'} -YamlValues @{} -SharedTarget $null -BackendPort 11031
$source=Get-Content (Join-Path $PSScriptRoot '../Start-Frontend.ps1') -Raw
if($source -match 'exec vite -- --host' -or $source -notmatch 'corepack\.cmd' -or $source -notmatch 'pnpm@\$\(\$spec.Pnpm\)'){throw 'Frontend must use pinned pnpm and forward Vite flags without an extra delimiter.'}
. (Join-Path $PSScriptRoot '../../config-pair/ConfigurationPair.ps1')
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$defaultValues=Read-ConfigPairValues -ProjectRoot $repoRoot
if($defaultValues['AIENIE_ADMIN_EMERGENCY_ACCEPTANCE'] -cne 'false' -or $defaultValues['SERVER_PORT'] -cne '11031'){throw 'Default YAML listener and disabled selector must remain canonical.'}
$fixturePair=Join-Path ([IO.Path]::GetTempPath()) ('social-acceptance-' + [guid]::NewGuid().ToString('N') + '.env')
$fixtureYaml=$fixturePair+'.application.yml'
try {
 @'
runtime:
  acceptance:
    admin-emergency-codes: true
  configuration:
    ENV: local
    AUTH_MODE: totp
    AIENIE_RUNTIME_PLANE: windows-local
server:
  address: 127.0.0.1
  port: 12031
'@ | Set-Content -LiteralPath $fixtureYaml -Encoding utf8
 $mapped=Read-ConfigPairValues -ProjectRoot $repoRoot -EnvironmentFile $fixturePair
 Assert-AdminEmergencyAcceptance -Values $mapped -YamlValues $mapped -SharedTarget $target -BackendPort 12031 -IsolatedAcceptance
} finally { Remove-Item -LiteralPath $fixtureYaml -ErrorAction SilentlyContinue }
Write-Output 'PASS explicit isolated startup acceptance, canonical defaults, rejection cases, real YAML mapping and pinned frontend forwarding (17 checks).'
