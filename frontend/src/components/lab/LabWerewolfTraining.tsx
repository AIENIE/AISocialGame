import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { adminLabApi, getApiErrorMessage } from "@/services/api";
import { Button } from "@/components/ui/button";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { LabStrategies } from "./LabStrategies";
import { LabEvaluationResults, LabLiveMatches } from "./LabPanels";
import type { LabExperiment, LabStrategyList } from "@/types/lab";
import type { CreateLabTrainingRun, LabTrainingControl, LabTrainingRun, LabTrainingTask } from "@/types/labTraining";

const steps=["选课程","局面练习","策略变化","对照评测","角色试用"];
const stages:Record<string,string>={CALIBRATION:"五项协议校准",PRACTICE:"局面练习与教学",MERGE:"合并策略变化",REHEARSAL:"练习回测",HOLDOUT:"独立保留题评测",GAMES:"完整对局对照",REPORT:"汇总评测结果"};
const statuses:Record<string,string>={DRAFT:"尚未开始",RUNNING:"进行中",PAUSED:"已暂停",COMPLETED:"本轮已收尾",STOPPED:"已停止",PENDING:"等待执行",WAITING:"等待原请求",SUCCEEDED:"已完成",FAILED:"失败",BLOCKED:"需要处理",SKIPPED:"已跳过"};
const kinds:Record<string,string>={CALIBRATION:"协议校准",PRACTICE:"玩家练习",TEACH:"教练诊断",REHEARSAL:"练习回测",HOLDOUT:"保留题回答",JUDGE:"盲评裁判"};
const failureCategories:Record<string,string>={INPUT_DEFECT:"输入缺陷",OUTPUT_PROTOCOL:"输出协议错误",ILLEGAL_ACTION:"非法动作",SEMANTIC_ERROR:"语义问题",CALL_FAILURE:"调用故障"};
const assessments:Record<string,string>={NOT_RUN:"尚未评测",INCOMPLETE:"评测未完整完成",NO_IMPROVEMENT:"未观察到改善",REGRESSION:"发现退步或新问题",IMPROVED:"在本次样本中观察到改善"};
const grade=(value:string)=>({PASS:"通过",FAIL:"发现问题",UNSCORED:"未评分"}[value]??value);
const seconds=(ms:number|null|undefined)=>typeof ms==="number"?`${(ms/1000).toFixed(1)} 秒`:"尚未记录";
function httpStatus(error:unknown):number|undefined {
  if(typeof error!=="object"||!error||!("response" in error))return;
  const response=error.response;
  if(typeof response==="object"&&response&&"status" in response&&typeof response.status==="number")return response.status;
}
function savedRequest(key:string):CreateLabTrainingRun|undefined {
  try {const value=JSON.parse(localStorage.getItem(key)??"null");if(value?.goal==="RULES_EVIDENCE"&&typeof value.operationId==="string")return value;} catch { /* Invalid browser state cannot authorize a run. */ }
}
function currentCase(id:string|undefined,replacements:Record<string,string>):string|undefined {
  const visited=new Set<string>();
  while(id&&replacements[id]) {
    if(visited.has(id))return;
    visited.add(id);id=replacements[id];
  }
  return id;
}
function pauseMessage(reason?:string|null) {
  if(reason==="PROTOCOL_CALIBRATION_FAILED")return "协议校准未通过，独立评测尚未启动。原回复与费用已保存，请先处理问题。";
  if(reason==="EVALUATION_IMPLEMENTATION_CHANGED")return "本轮冻结后的执行版本发生变化，评测已暂停，现有结果不能合并为独立通过。";
  if(reason==="REPRESENTATIVE_CASE_READY")return "代表案例的练习与教练诊断已完成。先查看回答、证据与建议，再决定是否继续全部训练。";
  if(reason==="REPRESENTATIVE_CASE_FAILED")return "代表案例未完成，训练已暂停。请先查看下方失败原因并处理；已成功的玩家回答继续保留。";
  if(reason?.includes("BUDGET"))return "已达到费用停止线，新调用已暂停；已发出的请求继续结算。";
  if(reason?.includes("UNKNOWN")||reason?.includes("RECONCILIATION"))return "当前调用结果或用量仍待确认，已保存现场。请查看原请求状态，不要另建训练重复生成。";
  return "训练已保留当前进度。请检查当前任务和诊断，再决定下一步。";
}
function TaskDetail({task,replacedBy}:{task:LabTrainingTask;replacedBy?:string}) {
  const result=task.result;
  const calls=task.diagnostics?.calls??[];
  const lastCall=calls.at(-1);
  const speech=result?.speech??(typeof result?.action?.content==="string"?result.action.content:undefined);
  const materialTooLarge=task.reason==="MATERIAL_TOO_LARGE";
  const confirmedUnsent=materialTooLarge&&!task.requestId&&Array.isArray(task.diagnostics?.calls)&&calls.length===0;
  return <article className="lab-training-task">
    {typeof task.diagnostics?.failureCategory==="string"&&failureCategories[task.diagnostics.failureCategory]&&<p className="lab-error mb-2">{failureCategories[task.diagnostics.failureCategory]}{Array.isArray(task.diagnostics.failureFlags)&&` · ${task.diagnostics.failureFlags.join(" / ")}`}</p>}
    <div className="lab-actions justify-between"><strong>{kinds[task.kind]??task.kind} · {task.caseId}</strong><span className="lab-state">{statuses[task.status]??task.status}</span></div>
    <p className="lab-small mt-2">{task.variant&&`${task.variant} · `}尝试 {task.attempt} · 已付费重试 {task.paidRetries}</p>
    {replacedBy&&<p className="lab-note mt-2">材料过大，已替换。原材料与任务记录保留，不计为额外开发题；当前案例：<span className="break-all">{replacedBy}</span>。</p>}
    {materialTooLarge&&!replacedBy&&<p className="lab-error mt-2" role="alert">输入材料超过 32000 Unicode 字符上限{typeof task.diagnostics?.inputCodePoints==="number"&&`（当前 ${task.diagnostics.inputCodePoints} 字符）`}。请先修复材料编码，再恢复当前训练。{confirmedUnsent&&"本任务未发送模型请求，未产生调用费用。"}</p>}
    {task.reason==="PAID_REDO_WAIT"&&typeof task.notBeforeEpochMs==="number"&&<p className="lab-note mt-2">独立计费重试等待：{task.notBeforeEpochMs>Date.now()?`还需 ${Math.ceil((task.notBeforeEpochMs-Date.now())/1000)} 秒` : "等待调度发送"}。原请求与费用记录继续保留。</p>}
    {lastCall&&<p className="lab-small mt-2">正文 TTFT：{seconds(lastCall.timing?.firstTextMs)} · 总耗时：{seconds(lastCall.timing?.totalMs)} · 此请求实扣：{typeof lastCall.billedPoints==="number"?`${lastCall.billedPoints} 积分`:"待确认"}</p>}
    {task.status==="RUNNING"&&task.startedAtEpochMs&&<p className="lab-small mt-2">当前任务已等待 {Math.max(0,Math.floor((Date.now()-task.startedAtEpochMs)/1000))} 秒</p>}
    {speech&&<div className="lab-memory mt-3"><h3>玩家回答</h3><p className="whitespace-pre-wrap break-words">{speech}</p></div>}
    {result?.diagnosis&&<div className="mt-3"><h3 className="font-medium">教练诊断</h3><p className="text-sm leading-7">{result.diagnosis.summary}</p><p className="lab-small">类型：{result.diagnosis.category} · 证据：{result.diagnosis.evidenceIds.join("、")||"未提供"}</p></div>}
    {!!result?.changes?.length&&<div className="space-y-3 mt-3">{result.changes.map((change,index)=><div className="lab-note" key={`${change.cardId??change.card?.id}:${index}`}><strong>{{ADD:"新增建议",UPDATE:"修改建议",RETIRE:"停用建议"}[change.operation]}</strong><p>{change.card?.advice??change.cardId}</p><p className="lab-small">原因：{change.reason} · 证据：{change.evidenceIds.join("、")||"未提供"}</p></div>)}</div>}
    {!!result?.assessments?.length&&<div className="lab-scroll mt-3"><table className="lab-history"><thead><tr><th>盲评回答</th><th>事实</th><th>证据</th><th>回应问题</th><th>目标错误</th></tr></thead><tbody>{result.assessments.map(value=><tr key={value.label}><td>{value.label} · {result.variantByLabel?.[value.label]??"对应关系未记录"}</td><td>{grade(value.facts)}</td><td>{grade(value.evidence)}</td><td>{grade(value.response)}</td><td>{value.targetErrors}</td></tr>)}</tbody></table></div>}
    <details className="lab-small mt-3"><summary>请求、用量与技术诊断</summary><p className="break-all mt-2">任务 {task.id} · 请求 {task.requestId??"尚未发送"}{task.reason&&` · ${task.reason}`}</p><pre className="lab-training-json">{JSON.stringify({diagnostics:task.diagnostics,result:task.result},null,2)}</pre></details>
  </article>;
}

export function LabWerewolfTraining({experiment,strategies,reload,budgetEditor,history}:{experiment:LabExperiment;strategies?:LabStrategyList;reload:()=>void;budgetEditor?:ReactNode;history?:ReactNode}) {
  const client=useQueryClient();
  const storageKey=`lab-training-run:${experiment.id}`;
  const [pending,setPending]=useState(()=>savedRequest(storageKey));
  const [selectedOperation,setSelectedOperation]=useState("");
  const [step,setStep]=useState<number>();
  const [message,setMessage]=useState("");
  const [controlUnknown,setControlUnknown]=useState(false);
  const [evaluationCandidate,setEvaluationCandidate]=useState("");
  const summary=experiment.trainingRuns?.find(item=>item.id===experiment.activeTrainingRunId)??experiment.trainingRuns?.[0];
  const operationId=pending?.operationId||selectedOperation||summary?.operationId;
  const queryKey=["admin","lab","training-run",experiment.id,operationId];
  const query=useQuery({queryKey,queryFn:({signal})=>adminLabApi.trainingRun(experiment.id,operationId!,signal),enabled:!!operationId,refetchInterval:5000,retry:false});
  const cases=useQuery({queryKey:["admin","lab","training-cases",experiment.id,operationId,JSON.stringify(query.data?.configuration.caseReplacements??{})],queryFn:({signal})=>adminLabApi.trainingCases(experiment.id,operationId!,signal),enabled:!!query.data,staleTime:60000});
  const run=query.data;
  useEffect(()=>{
    if(pending&&run?.operationId===pending.operationId){localStorage.removeItem(storageKey);setSelectedOperation(pending.operationId);setPending(undefined);setMessage("");reload();}
  },[run,pending,storageKey,reload]);
  const saveRun=(value:LabTrainingRun)=>{
    setSelectedOperation(value.operationId);client.setQueryData(["admin","lab","training-run",experiment.id,value.operationId],value);reload();
  };
  const create=useMutation({mutationFn:(request:CreateLabTrainingRun)=>adminLabApi.createTrainingRun(experiment.id,request),onSuccess:saveRun,onError:error=>{
    const status=httpStatus(error);
    if(status&&status>=400&&status<500&&status!==408){localStorage.removeItem(storageKey);setPending(undefined);}
    setMessage(getApiErrorMessage(error,"创建响应尚未确认，请查回原训练，不要再建一个。"));
  }});
  const control=useMutation({mutationFn:(action:LabTrainingControl)=>adminLabApi.controlTrainingRun(experiment.id,operationId!,action),onSuccess:value=>{setMessage("");setControlUnknown(false);saveRun(value);},onError:error=>{
    setControlUnknown(true);setMessage(getApiErrorMessage(error,"控制响应未确认，请先查询原训练状态。"));
  }});
  const createRun=()=>{
    if(pending||run||create.isPending)return;
    const request:CreateLabTrainingRun={operationId:crypto.randomUUID(),goal:"RULES_EVIDENCE"};
    try {localStorage.setItem(storageKey,JSON.stringify(request));} catch {setMessage("无法保存操作标识，本次未创建训练。请允许浏览器保存站点数据。");return;}
    setPending(request);setMessage("");create.mutate(request);
  };
  const createEvaluation=()=>{
    if(pending||create.isPending||!evaluationCandidate||run&&!['COMPLETED','STOPPED'].includes(run.status))return;
    const request:CreateLabTrainingRun={operationId:crypto.randomUUID(),goal:"RULES_EVIDENCE",mode:"EVALUATE_ONLY",candidateStrategyVersionId:evaluationCandidate,materialSetVersion:"rules-evidence-eval-v2.1"};
    try{localStorage.setItem(storageKey,JSON.stringify(request));}catch{setMessage("无法保存操作标识，本次未创建复测。");return;}
    setPending(request);setMessage("");create.mutate(request);
  };
  const refreshStatus=async()=>{const result=await query.refetch();if(result.isSuccess){setControlUnknown(false);setMessage("");reload();}};
  const activeStep=step??(!run||run.status==="DRAFT"?0:["CALIBRATION","PRACTICE"].includes(run.stage)?1:["MERGE","REHEARSAL"].includes(run.stage)?2:3);
  const tasks=run?.tasks??[];
  const taskCounts=tasks.reduce((counts,task)=>{
    if(task.status==="SUCCEEDED")counts.succeeded++;
    else if(task.status==="FAILED")counts.failed++;
    else if(task.status==="SKIPPED")counts.skipped++;
    return counts;
  },{succeeded:0,failed:0,skipped:0});
  const processedTasks=taskCounts.succeeded+taskCounts.failed+taskCounts.skipped;
  const caseReplacements={...cases.data?.caseReplacements,...run?.configuration.caseReplacements};
  const representativeCaseId=currentCase(cases.data?.items.find(item=>item.purpose==="DEVELOPMENT")?.id??tasks.find(task=>task.kind==="PRACTICE")?.caseId,caseReplacements);
  const activeCases=(cases.data?.items??[]).filter(item=>!caseReplacements[item.id]);
  const retainedCases=(cases.data?.items??[]).filter(item=>!!caseReplacements[item.id]);
  const currentTasks=run?.configuration.representativeOnly===true?tasks.filter(task=>task.caseId===representativeCaseId):tasks;
  const liveTask=currentTasks.find(task=>["RUNNING","WAITING"].includes(task.status))??currentTasks.find(task=>task.status==="BLOCKED")??(run?.status==="RUNNING"&&run.stage!=="GAMES"?currentTasks.find(task=>task.status==="PENDING"):undefined);
  const currentTask=liveTask??(run?.status==="PAUSED"?currentTasks.filter(task=>task.status==="FAILED").at(-1):undefined)??currentTasks.filter(task=>task.status==="SUCCEEDED").at(-1);
  const practice=tasks.filter(task=>["CALIBRATION","PRACTICE","TEACH","REHEARSAL"].includes(task.kind));
  const tests=tasks.filter(task=>["HOLDOUT","JUDGE"].includes(task.kind));
  const cost=run?.cost;
  const busy=control.isPending||controlUnknown;
  const remaining=cost?cost.budgetPoints-cost.totalKnownPoints:experiment.budgetPoints-experiment.cost.spentPoints;
  const assessment=run?.assessment;
  const assessmentPending=run?.stage==="HOLDOUT"&&run.status==="RUNNING"&&(!assessment?.status||assessment.status==="NOT_RUN");
  const assessmentLabel=assessmentPending?"正在评测，结果尚未汇总":assessments[assessment?.status??"NOT_RUN"]??"尚未评测";
  const runStatus=run?.status==="COMPLETED"&&assessment?.status==="INCOMPLETE"?"已收尾 · 评测未完成":statuses[run?.status??""];
  const scoredPairs=!assessmentPending&&typeof assessment?.completedPairs==="number"?assessment.completedPairs:undefined;
  const plannedPairs=typeof assessment?.plannedPairs==="number"?assessment.plannedPairs:run?.progress.holdoutPairs??32;
  const gameEvaluations=run?.candidateStrategyVersionId?(experiment.evaluations??[]).filter(value=>value.candidateStrategyVersionId===run.candidateStrategyVersionId):[];
  return <div className="lab-workspace lab-training-workspace">
    <Link to="/admin/lab" className="lab-small inline-block mb-5">← 返回实验室</Link>
    <header className="lab-heading"><div><div className="lab-eyebrow">WEREWOLF / TRAINING</div><h1>{experiment.name}</h1><p>先练习规则与证据，再用独立题目检验策略变化。</p></div>{run&&<span className="lab-state">{runStatus}</span>}</header>
    <nav className="lab-training-steps" aria-label="狼人杀训练步骤">{steps.map((label,index)=><button key={label} aria-current={activeStep===index?"step":undefined} onClick={()=>setStep(index)}><span>{index+1}</span>{label}</button>)}</nav>
    <div className="lab-columns"><main className="min-w-0">
      {run&&<section className="lab-training-status" aria-label="当前训练状态"><div className="lab-actions justify-between"><h2>{stages[run.stage]??run.stage}</h2><span className="lab-small">已处理 {processedTasks}/{tasks.length} · 成功 {taskCounts.succeeded} · 失败 {taskCounts.failed} · 跳过 {taskCounts.skipped}</span></div>
        {run.status==="PAUSED"&&<p className="lab-note mt-3" role="status">{pauseMessage(run.reason)}</p>}
        {run.nextStep&&<p className="lab-small mt-3">下一步：{run.nextStep}</p>}
        <div className="lab-actions mt-3">
          {run.status==="DRAFT"&&<><Button disabled={busy} onClick={()=>control.mutate("start")}>{run.configuration.mode==="EVALUATE_ONLY"?"开始校准与独立复测":"开始课程"}</Button>{run.configuration.mode!=="EVALUATE_ONLY"&&<Button variant="outline" disabled={busy} onClick={()=>control.mutate("start-one")}>试跑一个代表案例</Button>}</>}
          {run.status==="PAUSED"&&<Button disabled={busy||["PROTOCOL_CALIBRATION_FAILED","EVALUATION_IMPLEMENTATION_CHANGED"].includes(run.reason??"")} onClick={()=>control.mutate("resume")}>{run.reason==="REPRESENTATIVE_CASE_READY"?"案例已检查，继续训练":"继续当前训练"}</Button>}
          {run.status==="RUNNING"&&<Button variant="outline" disabled={busy} onClick={()=>control.mutate("pause")}>暂停训练</Button>}
          {!["COMPLETED","STOPPED"].includes(run.status)&&<Button variant="outline" disabled={busy} onClick={()=>control.mutate("stop")}>结束本次训练</Button>}
          <Button variant="ghost" disabled={query.isFetching} onClick={()=>void refreshStatus()}>查询原训练状态</Button>
        </div>
        {currentTask&&<details className="mt-4" open><summary>{liveTask?"当前任务":"最近保存任务"} · {kinds[currentTask.kind]} · {statuses[currentTask.status]}</summary><TaskDetail task={currentTask} replacedBy={caseReplacements[currentTask.caseId]&&currentCase(currentTask.caseId,caseReplacements)}/></details>}
      </section>}
      {message&&<p className="lab-error mt-3" role="alert">{message}</p>}
      {pending&&!run&&<div className="lab-note mt-3" role="status"><p>训练操作已保存 · {pending.operationId}。响应未确认时只查询这个操作。</p><div className="lab-actions mt-2"><Button variant="outline" disabled={query.isFetching||create.isPending} onClick={()=>void refreshStatus()}>查回原训练</Button>{httpStatus(query.error)===404&&<Button variant="outline" disabled={create.isPending} onClick={()=>create.mutate(pending)}>使用原操作 ID 重试创建</Button>}</div></div>}
      {operationId&&!run&&!pending&&<AdminDataState loading={query.isPending} error={query.error} retry={()=>void query.refetch()}/>}
      {run&&query.error&&<p className="lab-error mt-3">状态更新失败，下面保留最近一次结果；请查询原训练状态。</p>}
      {activeStep===0&&<section className="lab-section"><h2>规则与证据课程</h2><p className="text-sm leading-7">练习只依据当前身份可见的规则、发言和行动证据作答。教练逐题指出具体错误，提出可追溯的小幅策略修改。</p><div className="lab-note mt-4">引擎规则或可见信息错误需要修复产品；策略学习用于改进 AI 的判断与表达。一次训练不会改变模型参数，也不证明通用能力提升。</div>
        <dl className="lab-training-metrics mt-4"><div><dt>开发练习</dt><dd>{run?.progress.developmentCases??8} 个案例</dd></div><div><dt>独立保留题</dt><dd>{run?.progress.holdoutPairs??32} 对回答</dd></div><div><dt>完整对局</dt><dd>{run?.progress.gamePairs??2} 对样本</dd></div></dl>
        <p className="lab-small mt-4">开始课程后自动推进练习、策略合并、回测和独立评测。也可选择先试跑一个代表案例及诊断，暂停检查后再继续。训练与测试来源分别冻结；重复人格和同源题目不算独立来源。</p>
        {!run&&<Button className="mt-4" disabled={!!pending||create.isPending} onClick={createRun}>创建本次训练</Button>}
        {run&&["COMPLETED","STOPPED"].includes(run.status)&&<div className="lab-note mt-4"><label htmlFor="evaluation-candidate">独立复测候选版本</label><select id="evaluation-candidate" className="ml-3 rounded border p-2" value={evaluationCandidate} onChange={event=>setEvaluationCandidate(event.target.value)}><option value="">选择确切版本</option>{strategies?.items.map(item=><option key={item.id} value={item.id}>v{item.sequenceNumber} · {item.id}</option>)}</select><p className="lab-small mt-2">冻结所选版本，使用空策略基线。先完成五项协议校准，再评测新来源题；本轮不教学、不修改候选，费用沿用本实验累计停止线。</p><Button className="mt-3" disabled={!evaluationCandidate||!!pending||create.isPending} onClick={createEvaluation}>创建独立复测</Button></div>}
        {run?.configuration.mode==="EVALUATE_ONLY"&&<p className="lab-note mt-3">本轮为独立复测，候选版本固定；五项校准不计入策略改善样本。</p>}
        <details className="lab-small mt-4"><summary>阵容、模型与冻结配置</summary><p className="mt-3">玩家：{experiment.configuration?.roster[0]?.modelSelection.modelKey} · {experiment.configuration?.roster[0]?.modelSelection.thinkingMode} {experiment.configuration?.roster[0]?.modelSelection.thinkingEffort}</p><p>教练：{experiment.configuration?.coach.modelKey} · {experiment.configuration?.coach.thinkingMode} {experiment.configuration?.coach.thinkingEffort}</p><pre className="lab-training-json">{JSON.stringify(run?.configuration??experiment.configuration,null,2)}</pre></details>
      </section>}
      {activeStep===1&&<section className="lab-section"><h2>局面练习与逐题诊断</h2><p className="lab-small">这里展示开发练习和练习回测。完成回答只表示调用成功，判断质量以诊断和证据为准。</p>
        {practice.length?<div className="space-y-4 mt-4">{practice.map(task=><TaskDetail key={task.id} task={task} replacedBy={caseReplacements[task.caseId]&&currentCase(task.caseId,caseReplacements)}/>)}</div>:<p className="lab-note mt-4">尚无练习任务。创建训练后可直接开始课程，也可先试跑一个代表案例。</p>}
        <details className="lab-small mt-4"><summary>案例来源与覆盖</summary>{cases.data?<><p className="mt-2">当前开发练习 {activeCases.filter(item=>item.purpose==="DEVELOPMENT").length} 个案例 · 独立保留题 {activeCases.filter(item=>item.purpose==="HOLDOUT").length} 个单元；不将重复人格或同源题目算作独立来源。</p>
          {!!retainedCases.length&&<div className="lab-note mt-3"><p>已替换材料 {retainedCases.length} 份，不计入开发练习题数。</p>{retainedCases.map(item=><p className="break-all mt-2" key={item.id}>{item.id} · 材料过大，已替换 · 当前案例 {currentCase(item.id,caseReplacements)}</p>)}</div>}
          <pre className="lab-training-json">{JSON.stringify({coverage:cases.data.coverage,caseReplacements,items:cases.data.items.map(({data:_,...item})=>({...item,...(caseReplacements[item.id]?{materialStatus:"材料过大，已替换"}:{})}))},null,2)}</pre></>:<AdminDataState loading={cases.isPending} error={cases.error} retry={()=>void cases.refetch()}/>}</details>
      </section>}
      {activeStep===2&&<section className="lab-section"><h2>从诊断到策略变化</h2><p className="lab-small">逐条查看新增、修改和停用原因。只有合并成功后才产生本次候选版本，练习回测不算独立评测。</p>
        {tasks.filter(task=>task.kind==="TEACH"&&task.result?.changes?.length).map(task=><TaskDetail key={task.id} task={task}/>)}
        {strategies?<LabStrategies experimentId={experiment.id} data={strategies} reload={reload} view="strategy" versionId={run?.candidateStrategyVersionId??null}/>:<p className="lab-note mt-4">策略列表尚未取得，请稍后刷新。</p>}
      </section>}
      {activeStep===3&&<section className="lab-section"><h2>独立对照评测</h2><dl className="lab-training-metrics"><div><dt>保留题配对完成</dt><dd>{run?.progress.completedHoldoutPairs??0}/{run?.progress.holdoutPairs??32}</dd></div><div><dt>完整对局配对完成</dt><dd>{run?.progress.completedGamePairs??0}/{run?.progress.gamePairs??2}</dd></div></dl>
        <p className="lab-note mt-4">{assessmentLabel}。练习成绩与独立测试分开统计；少量完整对局仅作先导检查，不足以证明胜率提升。</p>
        {run?.coverage&&<p className="lab-small mt-2">计划 {run.coverage.plannedPairs} 对 · 完整评分 {run.coverage.scoredPairs} 对 · 尚无完整评分 {run.coverage.unscoredPairs} 对 · 玩家无效请求 {run.coverage.failedPlayerRequests} 次</p>}
        {assessment&&<p className="lab-small mt-3">{scoredPairs!==undefined&&`仅已评分 ${scoredPairs}/${plannedPairs} 对 · `}目标错误：基线 {assessmentPending?"未评分":assessment.baselineErrors??"未评分"} → 候选 {assessmentPending?"未评分":assessment.candidateErrors??"未评分"} · 新增规则错误 {assessmentPending?"未评分":assessment.newRuleErrors??"未评分"} · 新增证据越界 {assessmentPending?"未评分":assessment.newEvidenceLeaks??"未评分"}</p>}
        <details className="mt-4"><summary>保留题回答与盲评明细 · {tests.length} 项任务</summary><div className="space-y-4 mt-3">{tests.map(task=><TaskDetail key={task.id} task={task}/>)}</div></details>
        <section className="mt-6" aria-label="完整九人对局"><h3 className="font-medium">完整九人对局（达标后执行）</h3>
          <LabLiveMatches gameId="werewolf" matches={experiment.activeMatches??[]} adminExperimentId={experiment.id}/>
          {gameEvaluations.length?<LabEvaluationResults gameId="werewolf" evaluations={gameEvaluations}/>:<p className="lab-small mt-3">本次尚无完整对局结果。独立保留题完整评测并达到改善标准后，自动执行 {run?.progress.gamePairs??2} 组成对的九人对局。</p>}
        </section>
        {assessment&&<details className="lab-small mt-4"><summary>完整评测结论</summary><pre className="lab-training-json">{JSON.stringify(assessment,null,2)}</pre></details>}
      </section>}
      {activeStep===4&&<section className="lab-section"><h2>选择角色试用</h2><p className="lab-note">训练和评测均不会自动修改正式角色。试用后新开狼人杀对局会显示“效果待验证”，可随时撤回。</p>
        <p className="lab-small mt-3">本次评测：{assessmentLabel}。{run?.status!=="COMPLETED"?"本次流程尚未完成，建议先看完独立评测再决定。":"请结合样本量与错误明细决定是否试用。"}已知规则错误、证据越界或未解决的严重策略冲突会阻止发布。</p>
        {!!assessment?.conflicts?.length&&<details className="lab-error mt-3"><summary>策略冲突 · {assessment.conflicts.length} 项</summary><pre className="lab-training-json">{JSON.stringify(assessment.conflicts,null,2)}</pre></details>}
        {strategies?<LabStrategies experimentId={experiment.id} data={strategies} reload={reload} view="publication" versionId={run?.candidateStrategyVersionId??null}/>:<p className="lab-small">策略列表尚未取得。</p>}
      </section>}
      {history&&<details className="lab-section"><summary>历史对局与旧复盘记录（只读）</summary>{history}</details>}
    </main><aside className="lab-training-cost" aria-label="训练费用"><h2>费用与停止线</h2><dl className="mt-4 space-y-3"><div><dt>实验累计实扣</dt><dd>{cost?.spentPoints??experiment.cost.spentPoints} 积分</dd></div><div><dt>独立诊断计价</dt><dd>{cost?`${cost.externalPoints} 积分`:"尚未登记"}</dd></div><div><dt>已知费用合计</dt><dd>{cost?.totalKnownPoints??experiment.cost.spentPoints} 积分</dd></div><div><dt>实扣停止线</dt><dd>{cost?.budgetPoints??experiment.budgetPoints} 积分</dd></div><div><dt>距停止线</dt><dd>{remaining} 积分</dd></div><div><dt>待确认调用</dt><dd>{cost?.pendingCalls??experiment.cost.pendingCalls} 笔</dd></div></dl><p className="lab-small mt-4">包含历史费用。独立诊断单独登记，不重复扣款；待确认费用不当作免费。在途结算可能超过停止线。</p>{budgetEditor}
      {run&&<details className="lab-small mt-4"><summary>运行标识与状态</summary><p className="break-all mt-2">运行 {run.id}<br/>操作 {run.operationId}<br/>{run.status} · {run.stage}<br/>{run.reason}</p></details>}
    </aside></div>
  </div>;
}
