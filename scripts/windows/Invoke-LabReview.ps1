[CmdletBinding()]
param(
    [ValidateSet('Create','Poll','Export')][string]$Action='Poll',
    [Parameter(Mandatory)][string]$StatePath,
    [string]$ExperimentId,
    [string]$MatchId,
    [string]$CredentialFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne 'Win32NT'){throw 'Use the supported Windows local entrypoint.'}
$origin='https://localsocialgame.testhut.top'
if(!$CredentialFile){
    if(!$env:AIENIE_RUNTIME_ROOT){throw 'Set AIENIE_RUNTIME_ROOT or pass CredentialFile.'}
    $CredentialFile=Join-Path $env:AIENIE_RUNTIME_ROOT 'private/app-secrets/aisocialgame.env'
}
$StatePath=[IO.Path]::GetFullPath($StatePath)
$directory=Split-Path -Parent $StatePath
New-Item -ItemType Directory -Force -Path $directory|Out-Null
function Save-Review {
    $run.updatedAt=(Get-Date).ToUniversalTime().ToString('o')
    $temporary="$StatePath.tmp"
    ConvertTo-Json -InputObject $run -Depth 100|Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
    Move-Item -LiteralPath $temporary -Destination $StatePath -Force
}
if(Test-Path -LiteralPath $StatePath){
    $run=Get-Content -LiteralPath $StatePath -Raw|ConvertFrom-Json -AsHashtable -Depth 100
    if(($ExperimentId -and $ExperimentId -ne $run.experimentId) -or ($MatchId -and $MatchId -ne $run.matchId)){throw 'State file belongs to another review.'}
} else {
    if($Action -ne 'Create' -or !$ExperimentId -or !$MatchId){throw 'Create requires experiment and match IDs.'}
    $run=@{experimentId=([Guid]$ExperimentId).ToString();matchId=([Guid]$MatchId).ToString();operationId=[Guid]::NewGuid().ToString();
        coach=@{modelKey='gpt-6.1-sol';thinkingMode='ENABLED';thinkingEffort='MAX'};createAttempted=$false;
        sourceCommit=(& git -C (Join-Path $PSScriptRoot '../..') rev-parse HEAD).Trim()}
    Save-Review # The operation identity is durable before the first POST.
}
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$headers=@{Origin=$origin;Referer="$origin/admin/lab";Accept='application/json'}
function Invoke-ReviewApi([string]$Path,[string]$Method='GET',$Body=$null,[switch]$MissingAllowed) {
    $options=@{Uri="$origin$Path";Method=$Method;WebSession=$session;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=45}
    if($null -ne $Body){$options.Body=ConvertTo-Json -InputObject $Body -Depth 80 -Compress;$options.ContentType='application/json'}
    $response=Invoke-WebRequest @options
    if($MissingAllowed -and $response.StatusCode -eq 404){return $null}
    if($response.StatusCode -lt 200 -or $response.StatusCode -ge 300){
        $code='';try{$code=($response.Content|ConvertFrom-Json -AsHashtable).code}catch{}
        throw "Review API $Method $Path failed: HTTP $($response.StatusCode) $code"
    }
    return ConvertFrom-Json -InputObject $response.Content -AsHashtable -Depth 100
}
$env:LAB_REVIEW_CREDENTIAL_FILE=$CredentialFile
$payload=@'
import sys,json,re,os
from pathlib import Path
sys.path.insert(0,str(Path(sys.argv[1])/'support'))
import shared_target as st
v=st.environment(os.environ['LAB_REVIEW_CREDENTIAL_FILE'])
skill=Path.home()/'.codex/skills/local-debug-auth/SKILL.md'
password=re.search(r'管理员密码\s*`([^`]+)`',skill.read_text(encoding='utf-8')).group(1)
print(json.dumps({'username':v.get('APP_ADMIN_USERNAME') or v.get('ADMIN_USERNAME'),'password':password}))
'@ | python -X utf8 -B - $PSScriptRoot
Remove-Item Env:LAB_REVIEW_CREDENTIAL_FILE
$login=Invoke-ReviewApi '/api/admin/auth/login' 'POST' ($payload|ConvertFrom-Json -AsHashtable)
$payload=$null
if($login.state -ne 'AUTHENTICATED'){throw 'Existing local administrator login failed.'}
$login=$null
$path="/api/admin/lab/experiments/$($run.experimentId)/matches/$($run.matchId)/reviews"
$task=Invoke-ReviewApi "$path/$($run.operationId)" -MissingAllowed
if(!$task){
    if($Action -ne 'Create'){throw 'Original task not found; reuse Create with this state file to submit the same operation identity.'}
    $run.createAttempted=$true;Save-Review
    $task=Invoke-ReviewApi $path 'POST' @{operationId=$run.operationId;coach=$run.coach}
}
$run.task=$task;Save-Review
$progress=@{operationId=$run.operationId;taskId=$task.taskId;requestId=$task.requestId;status=$task.status;
    reason=$task['reason'];experimentStatus=$task.experimentStatus;callState=$task.call.state;billedPoints=$task.call['billedPoints'];timing=$task.call['timing']}
$progress|ConvertTo-Json -Depth 10
if($Action -eq 'Export'){
    if($task.status -ne 'SUCCEEDED' -or $task.experimentStatus -ne 'PAUSED' -or $task.call.state -ne 'SETTLED' -or !$task.call.usageAuthoritative){throw 'Review has not completed with authoritative usage and paused experiment.'}
    $detail=Invoke-ReviewApi "/api/admin/lab/experiments/$($run.experimentId)"
    $versions=[Collections.Generic.List[object]]::new()
    for($page=0;;$page++){
        $result=Invoke-ReviewApi "/api/admin/lab/experiments/$($run.experimentId)/experiences?page=$page"
        foreach($row in $result.items){$versions.Add($row)}
        if($versions.Count -ge $result.total){break}
        if(!$result.items.Count){throw 'Incomplete experience pagination.'}
    }
    $heads=$detail.configuration.experiences
    if($detail.completedGames -ne 1 -or $detail.reviewedGames -ne 1 -or $detail.abortedGames -ne 0 -or $versions.Count -ne 20 -or $heads.Count -ne 10 -or @($heads.Values|Where-Object sequenceNumber -ne 2).Count){throw 'Expected one normal game, 20 experience versions and 10 current v2 experiences.'}
    $result=@{experiment=$detail;review=$task;experienceVersions=$versions.ToArray();finalExperiences=$heads;sourceCommit=$run.sourceCommit;
        experimentUrl="$origin/admin/lab/$($run.experimentId)";exportedAt=(Get-Date).ToUniversalTime().ToString('o')}
    ConvertTo-Json -InputObject $result -Depth 100|Set-Content -LiteralPath (Join-Path $directory 'max-review-experiences.json') -Encoding utf8NoBOM
    $lines=[Collections.Generic.List[string]]::new()
    $lines.Add('# 第一局 MAX 教练复盘经验');$lines.Add('')
    $lines.Add("实验：[$($run.experimentId)]($origin/admin/lab/$($run.experimentId))，对局：$($run.matchId)。")
    $lines.Add('教练：gpt-6.1-sol / ENABLED / MAX，真实上游流式；原始输入沿用首次复盘快照。')
    $lines.Add("正文 TTFT：$($task.call.timing.firstTextMs) ms；首事件：$($task.call.timing.firstEventMs) ms；总耗时：$($task.call.timing.totalMs) ms。")
    $lines.Add("本次实扣：$($task.call.billedPoints) 积分（CNY $($task.call.billedCost)）；实验累计实扣：$($detail.cost.spentPoints)，待确认调用：$($detail.cost.pendingCalls)。")
    $lines.Add('结果：1 局正常结束，20 条经验版本，10 份当前 v2；HIGH v1 保留。实验保持暂停，未启动第二局。')
    foreach($profile in $detail.configuration.roster){
        $head=$heads[$profile.participantId];$version=@($versions|Where-Object id -eq $head.experienceId)[0]
        $lines.Add('');$lines.Add("## $($profile.name) · v$($version.sequenceNumber)");$lines.Add('')
        $lines.Add($version.summary);$lines.Add('')
        $lines.Add("副本：$($profile.participantId)；经验：$($version.id)；上一版 HIGH：$($version.previousId)。")
        $lines.Add("证据事件：$($version.evidence.eventIds -join ', ')。")
    }
    $lines|Set-Content -LiteralPath (Join-Path $directory 'max-review-experiences.md') -Encoding utf8NoBOM
}
