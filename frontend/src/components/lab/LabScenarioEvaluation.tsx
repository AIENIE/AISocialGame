import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { adminLabApi, getApiErrorMessage } from "@/services/api";
import { Button } from "@/components/ui/button";
import { LabState } from "./LabPanels";
import { labText } from "@/i18n/labTexts";
import type { LabScenarioRun } from "@/types/lab";

interface ScenarioRequest { operationId:string;candidateStrategyVersionId:string }
export function LabScenarioEvaluation({experimentId,candidateVersionId,runs,idle,caseCount=20,sourceGroupCount}:{experimentId:string;candidateVersionId:string;runs:Record<string,LabScenarioRun>;idle:boolean;caseCount?:number;sourceGroupCount?:number}) {
  const client=useQueryClient();
  const key=`lab-scenario-evaluation:${experimentId}`;
  const [request,setRequest]=useState<ScenarioRequest|undefined>(()=>{
    try {const value=JSON.parse(localStorage.getItem(key)??"null");return value&&typeof value.operationId==="string"&&typeof value.candidateStrategyVersionId==="string"?value:undefined;} catch {return undefined;}
  });
  const [localError,setLocalError]=useState("");
  const latest=Object.values(runs).slice(-1)[0];
  const operationId=request?.operationId??latest?.operationId??"";
  const queryKey=["admin","lab","scenario",experimentId,operationId];
  const start=useMutation({mutationFn:(value:ScenarioRequest)=>adminLabApi.startScenarios(experimentId,value),onSuccess:run=>{
    client.setQueryData(["admin","lab","scenario",experimentId,run.operationId],run);void client.invalidateQueries({queryKey:["admin","lab","experiment",experimentId]});
  }});
  const query=useQuery({queryKey,queryFn:()=>adminLabApi.scenarioRun(experimentId,operationId),enabled:!!operationId&&!start.isPending,refetchInterval:current=>["COMPLETED","STOPPED"].includes(current.state.data?.status??"")?false:2000,retry:1});
  const run=query.data??runs[operationId];
  const totalPairs=run?Math.ceil(run.totalCalls/2):caseCount;
  const unresolved=!!request&&!run;
  const results=run?.results??[];
  const pairIds=[...new Set(results.map(result=>result.caseId))];
  const completedPairs=pairIds.filter(caseId=>["BASELINE","CANDIDATE"].every(variant=>results.some(result=>result.caseId===caseId&&result.variant===variant))).length;
  const submit=()=>{
    const next={operationId:crypto.randomUUID(),candidateStrategyVersionId:candidateVersionId};
    try {localStorage.setItem(key,JSON.stringify(next));} catch {setLocalError("无法保存操作标识，本次未提交。请允许浏览器保存站点数据后重试。");return;}
    setLocalError("");setRequest(next);start.mutate(next);
  };
  return <section className="lab-section" aria-label="情境对照评测"><h2>{totalPairs} 组情境对照</h2>
    <p className="lab-small mb-3">使用创建实验时冻结的规则构造保留题（RULE_CONSTRUCTED_HOLDOUT），初始策略与候选策略分别回答同一情境，共 {totalPairs*2} 次调用，费用计入本实验。评测不写入训练经验。</p>
    {!!sourceGroupCount&&<p className="lab-small mb-3">{caseCount} 个情境单元来自 {sourceGroupCount} 种构造来源；同来源下的人格变体不计为独立来源。</p>}
    <p className="lab-note">效果待验证。合法动作与证据引用通过，只说明输出符合规则和引用边界；事实判断、策略正确性与说服效果仍待人工评阅。本评测不计算胜率。</p>
    <Button disabled={!idle||!caseCount||!candidateVersionId||candidateVersionId==="initial"||start.isPending||unresolved||run?.status==="RUNNING"||run?.status==="PAUSED"} onClick={submit}>开始 {caseCount} 组情境对照</Button>
    {unresolved&&request&&<div className="lab-note" role="status">请求结果待确认 · {request.operationId}<div className="lab-actions mt-2"><Button size="sm" variant="outline" disabled={start.isPending||query.isFetching} onClick={()=>void query.refetch()}>查询原情境评测</Button><Button size="sm" variant="outline" disabled={start.isPending} onClick={()=>start.mutate(request)}>使用原情境请求重试</Button></div></div>}
    {(localError||(!run&&start.error)||query.error)&&<p className="lab-error" role="alert">{localError||getApiErrorMessage(query.error??start.error,"情境评测状态读取或提交失败，原操作标识已保留")}</p>}
    {run&&<article className="lab-table mt-4"><div className="lab-actions"><LabState state={run.status}/><strong>已完成 {completedPairs} / {Math.ceil(run.totalCalls/2)} 组</strong><span className="lab-small">已结束尝试 {results.length} / {run.totalCalls}</span></div>
      {run.reason&&<p className="lab-error">{run.reason}</p>}{run.status==="PAUSED"&&<p className="lab-small mt-3">处理暂停原因后，继续本实验以恢复原情境任务。</p>}
      <p className="lab-small mt-3 break-all">候选策略：{run.candidateStrategyVersionId} · 操作 {run.operationId}</p>
      <div className="lab-scroll mt-3"><table className="lab-history"><thead><tr><th>版本</th><th>合法动作 / 已结束尝试</th><th>合法率</th><th>失败</th><th>带证据引用</th></tr></thead><tbody>{(["BASELINE","CANDIDATE"] as const).map(variant=>{
        const attempts=results.filter(result=>result.variant===variant),valid=attempts.filter(result=>result.status==="SUCCEEDED").length;
        return <tr key={variant}><td>{variant==="BASELINE"?"初始策略":"候选策略"}</td><td>{valid} / {attempts.length}</td><td>{attempts.length?`${(valid/attempts.length*100).toFixed(1)}%`:"—"}</td><td>{attempts.filter(result=>result.status==="FAILED").length}</td><td>{attempts.filter(result=>!!result.evidenceEventIds?.length).length}</td></tr>;
      })}</tbody></table></div>
      <details className="mt-3"><summary className="lab-small">查看逐题回答与引用，语义正确性待评阅</summary><div className="space-y-3 mt-3">{results.map(result=><article className="lab-table" key={`${result.caseId}:${result.variant}`}><h3 className="text-sm">{result.caseId} · {labText("zh-CN",result.role)} · {result.personaId} · {result.variant==="BASELINE"?"初始":"候选"} · {result.status==="SUCCEEDED"?"规则与引用通过":"失败"}</h3><p className="whitespace-pre-wrap break-words text-sm mt-2">{result.action?.content||result.action?.type||"没有有效动作"}</p>{result.action?.targetPlayerId&&<p className="lab-small">目标：{result.action.targetPlayerId}</p>}<p className="lab-small break-all mt-2">引用事件：{result.evidenceEventIds?.join(" · ")||"无"}</p></article>)}</div></details>
    </article>}
  </section>;
}
