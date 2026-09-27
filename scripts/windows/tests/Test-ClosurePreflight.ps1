[CmdletBinding()] param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../ClosureGrant.ps1')
function MustFail([scriptblock]$Action){$failed=$false;try{& $Action}catch{$failed=$true};if(-not $failed){throw 'Expected rejection'}}
$grant=@{approvedBy='test';approvalReference='synthetic-only';expiresAt=[DateTimeOffset]::UtcNow.AddMinutes(5).ToString('o');callerId='test';sourceFingerprint='a'*64;buildId='b'*64;batchId='test';model='deepseek-flash'}
Assert-ClosureGrant $grant
$grant.documentKind='UNAUTHORIZED_EXECUTION_PROPOSAL'
MustFail {Assert-ClosureGrant $grant}
$grant.Remove('documentKind');$grant.expiresAt=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o')
MustFail {Assert-ClosureGrant $grant}
MustFail {Assert-ClosureGrant @{authorized=$false}}
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('closure target test '+[guid]::NewGuid().ToString('N')+'.env')
try {
    @('ENV=local','AIENIE_RUNTIME_PLANE=windows-local','SPRING_DATASOURCE_URL=jdbc:mysql://localbase.testhut.top:13306/aisocialgame') | Set-Content -LiteralPath $fixture
    $script:dbCalls=0
    function java.exe {$script:dbCalls++;throw 'No Java/database execution allowed'}
    MustFail {& (Join-Path $PSScriptRoot '../Migrate-GameRealismV2.ps1') -EnvironmentFile $fixture}
    if($script:dbCalls -ne 0){throw 'Conflicting target reached a database helper'}
    Write-Host 'PASS proposal/stale-grant rejection and conflicting target blocks database execution.'
} finally {Remove-Item -LiteralPath $fixture -Force}
