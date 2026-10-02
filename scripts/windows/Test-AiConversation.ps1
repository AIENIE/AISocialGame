[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$ManifestFile,[Parameter(Mandatory)][string]$BundleFile,
 [Parameter(Mandatory)][string]$BudgetFile,[ValidateSet('Preflight','Pilot','Remaining')][string]$Phase='Preflight',
 [string]$ApprovalFile,[string]$PrerequisitesFile,[string]$EnvironmentFile,[string]$EvidenceFile,
 [string]$PriorEvidenceFile,[string]$PilotEvidenceFile,[string]$PilotReviewFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or $PSVersionTable.PSVersion.Major -lt 7){throw 'Requires Windows PowerShell 7.'}
. (Join-Path $PSScriptRoot 'LocalGrpcTrust.ps1')
$localGrpcTrust = Get-LocalGrpcTrustUri
$validationRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
function Private-Path([string]$Value,[bool]$Existing=$true) {
 if(-not [IO.Path]::IsPathFullyQualified($Value)){throw 'An absolute external path is required.'}
 $path=[IO.Path]::GetFullPath($Value)
 if($path.StartsWith($validationRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Raw evidence and authorization must remain outside the checkout.'}
 $item=Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($path))
 while($null -ne $item){if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse paths are not supported.'};$item=$item.Parent}
 if($Existing){$item=Get-Item -LiteralPath $path;if($item.PSIsContainer -or $item.Length -gt 32000000 -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid input file.'}}
 elseif(Test-Path -LiteralPath $path){throw 'Use a new evidence output file.'}
 return $path
}
$validationManifest=Private-Path $ManifestFile
$validationBundle=Private-Path $BundleFile
$validationLedger=Private-Path $BudgetFile
$validationJar=@(Get-ChildItem -LiteralPath (Join-Path $validationRoot 'backend/target') -Filter '*.jar')
if($validationJar.Count -ne 1){throw 'Exactly one verified application JAR is required.'}
$validationArguments=@((Join-Path $PSScriptRoot 'support/conversation_validation.py'),'--root',$validationRoot,'--jar',$validationJar[0].FullName,'--manifest',$validationManifest,'--bundle',$validationBundle,'--ledger',$validationLedger)
if($Phase -eq 'Preflight'){
 & python.exe @validationArguments --mode preflight
 if($LASTEXITCODE){throw 'Frozen manifest, build or ledger preflight failed.'}
 exit 0
}
$validationGrant=Private-Path $ApprovalFile
$validationProof=Private-Path $PrerequisitesFile
$validationOutput=Private-Path $EvidenceFile $false
$validationEnv=Private-Path $EnvironmentFile
$validationArguments+=@('--mode','gate','--phase',$Phase.ToUpperInvariant(),'--grant',$validationGrant,'--prerequisites',$validationProof)
$validationCanonical=@{
 ENV='local';APP_ENV='local';AIENIE_RUNTIME_PLANE='windows-local';APP_PROJECT_KEY='aisocialgame'
 AI_GRPC_ADDR='static://localaiservice.testhut.top:22011';AI_GRPC_NEGOTIATION_TYPE='TLS';GRPC_CLIENT_AI_SECURITY_TRUST_CERT_COLLECTION=$localGrpcTrust
 APP_AI_DEFAULT_MODEL='deepseek-flash';APP_AI_SYSTEM_USER_ID='85';APP_GAME_SCHEDULER_ENABLED='false';APP_DEMO_SEED_ENABLED='false';QDRANT_ENABLED='false'
 LOGGING_FILE_NAME='';LOGGING_LEVEL_ROOT='OFF';LOGGING_LEVEL_APP='OFF';AI_CONVERSATION_REAL='1'
 AI_CONVERSATION_MANIFEST=$validationManifest;AI_CONVERSATION_BUNDLE=$validationBundle;AI_CONVERSATION_GRANT=$validationGrant
 AI_CONVERSATION_OUTPUT=$validationOutput;AI_CONVERSATION_PHASE=$Phase.ToUpperInvariant();AI_CONVERSATION_JAR=$validationJar[0].FullName
 AI_CONVERSATION_PREREQUISITES=$validationProof;AI_REALISM_BUDGET_FILE=$validationLedger
}
if($PriorEvidenceFile){$validationCanonical.AI_CONVERSATION_PRIOR=Private-Path $PriorEvidenceFile;$validationArguments+=@('--prior',$validationCanonical.AI_CONVERSATION_PRIOR)}
if($Phase -eq 'Remaining'){
 $validationCanonical.AI_CONVERSATION_PILOT=Private-Path $PilotEvidenceFile
 $validationCanonical.AI_CONVERSATION_REVIEW=Private-Path $PilotReviewFile
 $validationArguments+=@('--evidence',$validationCanonical.AI_CONVERSATION_PILOT,'--review',$validationCanonical.AI_CONVERSATION_REVIEW)
}
& python.exe @validationArguments
if($LASTEXITCODE){throw 'Authorization, freshness or pilot gate rejected collection. No model request issued.'}
$validationValues=@{}
foreach($line in [IO.File]::ReadAllLines($validationEnv)){
 if([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')){continue}
 if($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){throw 'Expected literal NAME=value entries.'}
 $key=$Matches[1];$value=$Matches[2]
 if($key -match '^(SPRING_CONFIG_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|MAVEN_OPTS$|MAVEN_ARGS$|PATH$|PSMODULEPATH$|COMSPEC$)'){throw 'Private environment cannot override process control settings.'}
 if($validationValues.ContainsKey($key)){throw 'Duplicate setting.'}
 if($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))){$value=$value.Substring(1,$value.Length-2)}
 $validationValues[$key]=$value
}
. (Join-Path $validationRoot 'scripts/config-pair/ConfigurationPair.ps1')
$validationYaml=Read-ConfigPairValues -ProjectRoot $validationRoot -EnvironmentFile $validationEnv
foreach($key in $validationYaml.Keys){$validationValues[$key]=$validationYaml[$key]}
if(-not (Test-Path -LiteralPath ($validationEnv+'.application.yml') -PathType Leaf)){throw 'Persistent acceptance requires the private local configuration pair.'}
$validationValues.AIENIE_APPLICATION_FILE=([Uri][IO.Path]::GetFullPath($validationEnv+'.application.yml')).AbsoluteUri
foreach($entry in $validationValues.GetEnumerator()) {
 if(-not $validationCanonical.ContainsKey($entry.Key)){$validationCanonical[$entry.Key]=[string]$entry.Value}
}
$validationCanonical.SPRING_PROFILES_ACTIVE='local'
$validationCanonical.APP_AI_BUDGET_ENABLED='true'
$validationCanonical.APP_AI_BUDGET_MAX_OUTPUT_TOKENS='1024'
$validationCanonical.APP_AI_VALIDATION_CALL_LIMIT='0'
foreach($key in @('ENV','AI_GRPC_ADDR','AI_GRPC_NEGOTIATION_TYPE','APP_AI_DEFAULT_MODEL')){if($validationValues[$key] -cne $validationCanonical[$key]){throw 'Noncanonical environment, model or TLS target.'}}
$validationApproved=Get-Content -LiteralPath $validationGrant -Raw | ConvertFrom-Json -AsHashtable
if($validationValues.APP_EXTERNAL_AISERVICE_HMAC_CALLER -cne $validationApproved.callerId -or [string]::IsNullOrWhiteSpace($validationValues.GRPC_SHARED_SECRET)){throw 'Original caller credentials do not match the grant.'}
$validationCanonical.APP_EXTERNAL_AISERVICE_HMAC_CALLER=$validationValues.APP_EXTERNAL_AISERVICE_HMAC_CALLER
$validationCanonical.GRPC_SHARED_SECRET=$validationValues.GRPC_SHARED_SECRET
$validationPrevious=@{}
try{
 foreach($name in @([Environment]::GetEnvironmentVariables('Process').Keys)){
  if([string]$name -match '^(APP_|AI_|AIENIE_|REAL_|REALISM_|E2E_|GRPC_|SPRING_|USER_GRPC_|BILLING_GRPC_|SSO_|QDRANT_|LOGGING_|SERVER_|ADMIN_)' -or [string]$name -in @('ENV','AUTH_MODE','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS')){
   $validationPrevious[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$null,'Process')
  }
 }
 foreach($entry in $validationCanonical.GetEnumerator()){
  if(-not $validationPrevious.ContainsKey($entry.Key)){$validationPrevious[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key,'Process')}
  [Environment]::SetEnvironmentVariable($entry.Key,[string]$entry.Value,'Process')
 }
 & mvn.cmd -q -f (Join-Path $validationRoot 'backend/pom.xml') '-Dtest=ConversationValidationRealIntegrationTest#collectWithFrozenInputsOriginalJournalAndExplicitGrant' '-DdisableXmlReport=true' '-Dsurefire.useFile=false' '-Dmaven.test.redirectTestOutputToFile=false' test *> $null
 if($LASTEXITCODE -or -not (Test-Path -LiteralPath $validationOutput)){throw 'Collection stopped or skipped; preserve evidence and journal. No automatic retry.'}
 Write-Host 'Evidence requires review and durable credit reconciliation. Shared caller state remains unchanged.'
}finally{
 foreach($entry in $validationPrevious.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
}
