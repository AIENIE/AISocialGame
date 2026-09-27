[CmdletBinding()]param()
$ErrorActionPreference='Stop'
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('conversation script test '+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$oldMarker=[Environment]::GetEnvironmentVariable('APP_CONVERSATION_SENTINEL','Process')
$oldEnabled=[Environment]::GetEnvironmentVariable('AI_CONVERSATION_REAL','Process')
$oldCaller=[Environment]::GetEnvironmentVariable('APP_EXTERNAL_AISERVICE_HMAC_CALLER','Process')
try{
 foreach($name in @('manifest','bundle','ledger','proof')){'{}' | Set-Content -LiteralPath (Join-Path $testRoot "$name.json")}
 '{"callerId":"synthetic-only"}' | Set-Content -LiteralPath (Join-Path $testRoot 'grant.json')
 @('ENV=local','AI_GRPC_ADDR=static://localaiservice.testhut.top:22011','AI_GRPC_NEGOTIATION_TYPE=TLS','APP_AI_DEFAULT_MODEL=deepseek-flash','APP_EXTERNAL_AISERVICE_HMAC_CALLER=synthetic-only','APP_EXTERNAL_AISERVICE_HMAC_SECRET=synthetic-no-secret') | Set-Content -LiteralPath (Join-Path $testRoot 'test.env')
 $env:APP_CONVERSATION_SENTINEL='restore-me';$env:AI_CONVERSATION_REAL='must-be-restored'
 $global:AiConversationTestCalls=@{python=0;maven=0}
 function python.exe {$global:AiConversationTestCalls.python++;$global:LASTEXITCODE=0;'{"status":"SYNTHETIC_GATE"}'}
 function mvn.cmd {
  $global:AiConversationTestCalls.maven++
  if($env:AI_CONVERSATION_REAL -cne '1' -or $env:APP_CONVERSATION_SENTINEL -or $env:APP_EXTERNAL_AISERVICE_HMAC_CALLER -cne 'synthetic-only'){throw 'Isolation failed'}
  $global:LASTEXITCODE=1
 }
 $failed=$false
 try{
  & (Join-Path $PSScriptRoot '../Test-AiConversation.ps1') -Phase Pilot -ManifestFile (Join-Path $testRoot 'manifest.json') -BundleFile (Join-Path $testRoot 'bundle.json') -BudgetFile (Join-Path $testRoot 'ledger.json') -ApprovalFile (Join-Path $testRoot 'grant.json') -PrerequisitesFile (Join-Path $testRoot 'proof.json') -EnvironmentFile (Join-Path $testRoot 'test.env') -EvidenceFile (Join-Path $testRoot 'new evidence.json')
 }catch{$failed=$true;$testFailure=$_.Exception.Message}
 if(-not $failed -or $global:AiConversationTestCalls.python -ne 1 -or $global:AiConversationTestCalls.maven -ne 1){throw "Mock collection was not exercised: python=$global:AiConversationTestCalls.python maven=$global:AiConversationTestCalls.maven reason=$testFailure"}
 if($env:APP_CONVERSATION_SENTINEL -cne 'restore-me' -or $env:AI_CONVERSATION_REAL -cne 'must-be-restored' -or [string][Environment]::GetEnvironmentVariable('APP_EXTERNAL_AISERVICE_HMAC_CALLER','Process') -cne [string]$oldCaller){throw ('Environment restore flags: sentinel={0} enabled={1} caller={2}' -f ($env:APP_CONVERSATION_SENTINEL -ceq 'restore-me'),($env:AI_CONVERSATION_REAL -ceq 'must-be-restored'),([string][Environment]::GetEnvironmentVariable('APP_EXTERNAL_AISERVICE_HMAC_CALLER','Process') -ceq [string]$oldCaller))}
 Write-Host 'PASS conversation entry: paths with spaces, isolated mock invocation, failure restores environment.'
}finally{
 [Environment]::SetEnvironmentVariable('APP_CONVERSATION_SENTINEL',$oldMarker,'Process')
 [Environment]::SetEnvironmentVariable('AI_CONVERSATION_REAL',$oldEnabled,'Process')
 [Environment]::SetEnvironmentVariable('APP_EXTERNAL_AISERVICE_HMAC_CALLER',$oldCaller,'Process')
 Remove-Variable -Name AiConversationTestCalls -Scope Global -ErrorAction SilentlyContinue
 # Only individually named synthetic files are removed; no recursive computed deletion.
 foreach($name in @('manifest.json','bundle.json','ledger.json','proof.json','grant.json','test.env')){Remove-Item -LiteralPath (Join-Path $testRoot $name) -ErrorAction SilentlyContinue}
 Remove-Item -LiteralPath $testRoot -ErrorAction SilentlyContinue
}
