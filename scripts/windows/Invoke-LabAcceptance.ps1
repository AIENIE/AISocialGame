[CmdletBinding()]
param(
    [ValidateSet('Create','Run','Poll','Export')][string]$Action='Poll',
    [Parameter(Mandatory)][string]$StatePath,
    [string[]]$BaseAgentIds,
    [int]$Seed=20261022,
    [string]$CredentialFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne 'Win32NT'){throw 'Use the supported Windows local entrypoint.'}
$origin='https://localsocialgame.testhut.top'
if(!$CredentialFile){
    if(!$env:AIENIE_RUNTIME_ROOT){throw 'Set AIENIE_RUNTIME_ROOT or pass the approved private CredentialFile.'}
    $CredentialFile=Join-Path $env:AIENIE_RUNTIME_ROOT 'private/app-secrets/aisocialgame.env'
}
$StatePath=[IO.Path]::GetFullPath($StatePath)
$outputDirectory=Split-Path -Parent $StatePath
New-Item -ItemType Directory -Force -Path $outputDirectory|Out-Null
$apiSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$apiHeaders=@{Origin=$origin;Referer="$origin/admin/lab";Accept='application/json'}

function Invoke-LabApi([string]$Path,[string]$Method='GET',$Body=$null) {
    $options=@{Uri="$origin$Path";Method=$Method;WebSession=$apiSession;Headers=$apiHeaders;SkipHttpErrorCheck=$true;TimeoutSec=45}
    if($null -ne $Body){$options.Body=ConvertTo-Json -InputObject $Body -Depth 80 -Compress;$options.ContentType='application/json'}
    $response=Invoke-WebRequest @options
    if($response.StatusCode -lt 200 -or $response.StatusCode -ge 300){
        $code='';try{$code=($response.Content|ConvertFrom-Json -AsHashtable).code}catch{}
        throw "Lab API $Method $Path failed: HTTP $($response.StatusCode) $code"
    }
    if($response.Content){return ConvertFrom-Json -InputObject $response.Content -AsHashtable -Depth 100}
}
function Save-Run {
    $temporary="$StatePath.tmp"
    $run.updatedAt=(Get-Date).ToUniversalTime().ToString('o')
    ConvertTo-Json -InputObject $run -Depth 100|Set-Content -LiteralPath $temporary -Encoding utf8
    Move-Item -LiteralPath $temporary -Destination $StatePath -Force
}
function Get-AllPages([string]$Path) {
    $rows=[Collections.Generic.List[object]]::new()
    for($page=0;;$page++){
        $response=Invoke-LabApi "${Path}?page=$page"
        foreach($row in $response.items){$rows.Add($row)}
        if($rows.Count -ge $response.total){break}
        if($response.items.Count -eq 0){throw 'Incomplete paginated API result'}
    }
    return ,$rows.ToArray()
}
function Get-Progress {
    $detail=Invoke-LabApi "/api/admin/lab/experiments/$($run.experimentId)"
    $matches=Get-AllPages "/api/admin/lab/experiments/$($run.experimentId)/matches"
    $progress=@{experimentId=$run.experimentId;status=$detail.status;reason=$detail['reason'];completedGames=$detail.completedGames;
        reviewedGames=$detail.reviewedGames;abortedGames=$detail.abortedGames;cost=$detail.cost;active=@()}
    foreach($match in $detail.activeMatches){
        $observed=Invoke-LabApi "/api/admin/lab/experiments/$($run.experimentId)/matches/$($match.id)/spectate"
        $progress.active+=@{matchId=$match.id;sequence=$match.sequenceNumber;status=$match.status;
            phase=$observed.state.phase;round=$observed.state.round;currentSpeaker=$observed.state['currentSpeakerName']}
    }
    $run.progress=$progress;Save-Run
    return @{detail=$detail;matches=$matches;progress=$progress}
}
function Export-Results($snapshot) {
    $detail=$snapshot.detail;$matches=$snapshot.matches
    if($detail.status -ne 'COMPLETED' -or $matches.Count -ne 10 -or @($matches|Where-Object {$_.status -ne 'REVIEWED' -or $_['reason']}).Count){throw 'Acceptance requires the same experiment to finish all 10 games and all reviews.'}
    $versions=Get-AllPages "/api/admin/lab/experiments/$($run.experimentId)/experiences"
    $heads=$detail.configuration.experiences
    if($versions.Count -ne 100 -or $heads.Count -ne 10 -or @($heads.Values|Where-Object {$_.sequenceNumber -ne 10}).Count){throw 'Acceptance requires 100 experience versions and 10 final experiences.'}
    $calls=@();foreach($match in $matches){
        $diagnostics=Invoke-LabApi "/api/admin/lab/experiments/$($run.experimentId)/matches/$($match.id)/diagnostics"
        foreach($call in $diagnostics.calls){$calls+=@{matchId=$match.id;sequenceNumber=$match.sequenceNumber;requestId=$call.requestId;state=$call.state;billedPoints=$call.billedPoints;purpose=$call.purpose}}
    }
    if($detail.cost.pendingCalls -ne 0 -or @($calls|Where-Object {$_.state -notin @('SETTLED','RELEASED')}).Count){throw 'Some original requests do not yet have a final settlement.'}
    $sum=($calls|Measure-Object -Property billedPoints -Sum).Sum
    if($sum -ne $detail.cost.spentPoints){throw 'Experiment spending does not match the original request receipts.'}
    $result=@{runId=$run.runId;seed=$run.seed;sourceCommit=$run.sourceCommit;experimentUrl="$origin/admin/lab/$($run.experimentId)";
        experiment=$detail;matches=$matches;experienceVersions=$versions;finalExperiences=$heads;calls=$calls;exportedAt=(Get-Date).ToUniversalTime().ToString('o')}
    ConvertTo-Json -InputObject $result -Depth 100|Set-Content -LiteralPath (Join-Path $outputDirectory 'final-experiences.json') -Encoding utf8
    $lines=[Collections.Generic.List[string]]::new()
    $lines.Add('# 10 人、10 局实验最终经验')
    $lines.Add('')
    $lines.Add("实验：[$($run.experimentId)]($origin/admin/lab/$($run.experimentId))")
    $lines.Add("配置：10 AI，白板开启，daily 词库，随机种子 $($run.seed)，空初始经验。")
    $lines.Add('玩家：deepseek-flash / ENABLED / LOW；教练：gpt-6.1-sol / ENABLED / MAX。')
    $lines.Add("结果：10 局正常结束，10 次复盘，100 条经验版本，10 份最终经验；实扣 $($detail.cost.spentPoints) 积分，未结调用 $($detail.cost.pendingCalls)。")
    $lines.Add("运行提交：$($run.sourceCommit)。")
    foreach($player in $detail.configuration.roster){
        $head=$heads[$player.participantId]
        $lines.Add('');$lines.Add("## $($player.name)");$lines.Add('')
        $lines.Add("副本：$($player.participantId)；人格：$($player.personaId)；经验版本：$($head.sequenceNumber)；版本 ID：$($head.experienceId)。")
        $lines.Add('');$lines.Add($head.experience);$lines.Add('')
        $lines.Add('| 版本 | 来源对局 | 经验 ID | 证据事件 |');$lines.Add('| --- | --- | --- | --- |')
        foreach($version in @($versions|Where-Object participantId -eq $player.participantId|Sort-Object sequenceNumber)){
            $lines.Add("| $($version.sequenceNumber) | $($version.evidence.matchId) | $($version.id) | $($version.evidence.eventIds -join ', ') |")
        }
    }
    $lines|Set-Content -LiteralPath (Join-Path $outputDirectory 'final-experiences.md') -Encoding utf8
    $run.accepted=$true;$run.exports=@('final-experiences.json','final-experiences.md');Save-Run
}

# Credentials stay in memory, sourced from the existing private local configuration/skill.
$env:LAB_ACCEPTANCE_CREDENTIAL_FILE=$CredentialFile
$authPayload=@'
import sys,json,re,os
from pathlib import Path
sys.path.insert(0,str(Path(sys.argv[1])/'support'))
import shared_target as st
v=st.environment(os.environ['LAB_ACCEPTANCE_CREDENTIAL_FILE'])
skill=Path.home()/'.codex/skills/local-debug-auth/SKILL.md'
password=re.search(r'管理员密码\s*`([^`]+)`',skill.read_text(encoding='utf-8')).group(1)
print(json.dumps({'username':v.get('APP_ADMIN_USERNAME') or v.get('ADMIN_USERNAME'),'password':password}))
'@ | python -X utf8 -B - $PSScriptRoot
Remove-Item Env:LAB_ACCEPTANCE_CREDENTIAL_FILE
$login=Invoke-WebRequest "$origin/api/admin/auth/login" -Method Post -Body $authPayload -ContentType application/json -Headers $apiHeaders -WebSession $apiSession -SkipHttpErrorCheck
$authPayload=$null
if($login.StatusCode -ne 200 -or ($login.Content|ConvertFrom-Json).state -ne 'AUTHENTICATED'){throw 'Existing local administrator login failed.'}
$login=$null

if(Test-Path -LiteralPath $StatePath){$run=Get-Content -LiteralPath $StatePath -Raw|ConvertFrom-Json -AsHashtable -Depth 100}
else {
    if($Action -ne 'Create' -or $BaseAgentIds.Count -ne 4){throw 'Create requires exactly four basic profile IDs and a new state file.'}
    $agents=Invoke-LabApi '/api/admin/lab/agents'
    $basic=@($BaseAgentIds|ForEach-Object {$requested=$_;$found=@($agents|Where-Object id -eq $requested);if($found.Count -ne 1){throw 'Basic profile not found'};$found[0]})
    if(@($basic.personaId|Sort-Object -Unique).Count -ne 4 -or @($basic|Where-Object {$_.name -like '*重复角色*'}).Count){throw 'Select the four distinct basic personas.'}
    $random=[Random]::new($Seed)
    $participants=@(0..9|ForEach-Object {@{agentId=$basic[$random.Next(4)].id;selection=@{modelKey='deepseek-flash';thinkingMode='ENABLED';thinkingEffort='LOW'}}})
    $runId=[Guid]::NewGuid().ToString()
    $run=@{runId=$runId;seed=$Seed;sourceCommit=(& git -C (Join-Path $PSScriptRoot '../..') rev-parse HEAD).Trim();experimentId=$null;createAttempted=$false;
        configuration=@{name="10人10局真实验收 · $($runId.Substring(0,8))";playerCount=10;targetGames=10;wordPack='daily';hasBlank=$true;budgetPoints=10000;
            participants=$participants;coach=@{modelKey='gpt-6.1-sol';thinkingMode='ENABLED';thinkingEffort='MAX'}}}
    Save-Run
}
if(!$run.experimentId){
    $existing=@((Get-AllPages '/api/admin/lab/experiments')|Where-Object name -eq $run.configuration.name)
    if($existing.Count -gt 1){throw 'Ambiguous create recovery; refusing another experiment.'}
    if($existing.Count -eq 1){$run.experimentId=$existing[0].id;Save-Run}
    elseif($run.createAttempted){throw 'Original creation outcome is still unknown. No duplicate was created; inspect the existing request before retrying.'}
    elseif($Action -eq 'Create'){
        $run.createAttempted=$true;Save-Run
        $created=Invoke-LabApi '/api/admin/lab/experiments' 'POST' $run.configuration
        $run.experimentId=$created.id;Save-Run
    } else {throw 'Experiment has not been created.'}
}
if($Action -eq 'Create'){Write-Output (ConvertTo-Json @{experimentId=$run.experimentId;seed=$run.seed;state=$StatePath} -Compress);exit 0}
if($Action -eq 'Run'){
    $detail=Invoke-LabApi "/api/admin/lab/experiments/$($run.experimentId)"
    if($detail.status -eq 'DRAFT'){$null=Invoke-LabApi "/api/admin/lab/experiments/$($run.experimentId)/control/start" 'POST'}
}
$last=''
do {
    $snapshot=Get-Progress
    $compact=ConvertTo-Json -InputObject $snapshot.progress -Depth 8 -Compress
    if($compact -ne $last){Write-Output $compact;$last=$compact}
    if($snapshot.detail.status -eq 'COMPLETED' -or $Action -eq 'Export'){Export-Results $snapshot;Write-Output 'ACCEPTED: 10 games, 10 reviews, 100 versions, 10 final experiences; all calls settled.';break}
    if($Action -ne 'Run'){break}
    if($snapshot.detail.status -in @('PAUSED','STOPPED') -or $snapshot.detail.abortedGames -gt 0){throw "Experiment needs attention: $($snapshot.detail.status) $($snapshot.detail['reason']). State preserved at $StatePath"}
    Start-Sleep -Seconds 10
} while($true)
