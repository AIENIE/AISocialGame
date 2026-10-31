import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { AlertTriangle, ChevronDown } from "lucide-react";
import { adminLabApi } from "@/services/api";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { failureText } from "./labFailures";

export function LabDiagnostics({experimentId,matchId}:{experimentId:string;matchId:string}) {
  const query=useQuery({queryKey:["admin","lab","diagnostics",experimentId,matchId],queryFn:({signal})=>adminLabApi.diagnostics(experimentId,matchId,signal),refetchInterval:5000});
  if(!query.data) return <AdminDataState loading={query.isPending} error={query.error} retry={()=>void query.refetch()}/>;
  const data=query.data,issue=data.failure;
  const spent=data.calls.reduce((total,call)=>total+(call.billedPoints??0),0);
  const pending=data.calls.filter(call=>call.billedPoints===null).length;
  return <section className="lab-diagnostics" id="lab-diagnostics" aria-label="对局诊断">
    <div className="flex items-center gap-2"><AlertTriangle size={20}/><h2>{issue?failureText(issue.code):"对局诊断"} · 第 {data.sequenceNumber} 局</h2></div>
    {issue&&<><p className="mt-3">{issue.code==="FORBIDDEN_WORD_ELIMINATED"?"角色三次提交命中本人禁词，已按规则出局，对局可以继续。":issue.code==="LAB_BUDGET_EXHAUSTED"?"实验已达到当前预算上限并暂停，请核对已扣费用和原请求状态。":issue.code==="BUDGET_RECONCILIATION_REQUIRED"?"这是当时记录的调用状态；下方显示原请求的当前结算状态，用量尚未确认的请求会单独标明，不占用积分。":issue.stage==="REVIEW"?"本局已结束，但复盘未成功，已有经验仍保留。处理原因后使用“重试复盘并继续”。":"请查看下方已记录的动作和调用信息。"}</p>
      <dl className="lab-diagnostic-facts"><div><dt>发生位置</dt><dd>第 {issue.round||"—"} 轮 · {issue.phase||"—"}</dd></div><div><dt>涉及副本</dt><dd>{issue.actorName||"未记录"}</dd></div><div><dt>模型</dt><dd>{issue.modelKey||"未记录"}</dd></div><div><dt>失败阶段</dt><dd>{({SUBMISSION:"动作提交",GENERATION:"生成或校验",REVIEW:"教练复盘",EXECUTION:"执行",RULE:"游戏规则判罚"})[issue.stage]||issue.stage}</dd></div></dl>
      {issue.action&&<div className="lab-failed-action"><strong>被拒绝的动作 · {issue.action.type}</strong><p>{issue.action.content}</p></div>}
      {!issue.action&&<p className="lab-small mt-3">当时未保存可用的失败动作；仅显示已记录的原因，不推测原文。</p>}
    </>}
    <p className="mt-3 text-sm">本局已扣 <strong>{spent} 积分</strong>（¥{(spent/100).toFixed(2)}） · 用量待确认 <strong>{pending} 笔</strong>。已返回但提交失败的调用仍按实际用量结算。</p>
    <div className="lab-actions mt-3">{data.archiveId&&<Link className="underline" to={"/admin/replays/"+data.archiveId}>查看本局完整回放</Link>}</div>
    <details className="mt-4"><summary className="flex cursor-pointer items-center gap-2 text-sm"><ChevronDown size={14}/>技术详情与原请求账务</summary>
      {issue&&<p className="lab-small mt-3 break-all">原因码：{issue.code} · 任务：{issue.jobId||"未记录"}</p>}
      <div className="lab-scroll"><table className="lab-history"><thead><tr><th>AI 副本 / 模型</th><th>状态 / 原因</th><th>调用尝试</th></tr></thead><tbody>{data.tasks.map(task=><tr key={task.jobId}><td>{task.actorName}<br/>{task.modelKey}</td><td>{task.status}<br/>{failureText(task.reason)}</td><td>{task.attempts.map((attempt,index)=><div key={`${attempt.requestId}:${attempt.attempt}:${index}`}>{attempt.attempt+1} · {attempt.flags?.join(" / ")||"通过生成校验"} {attempt.failureStage} · 当前结算：{attempt.settlementState||"未记录"}</div>)}</td></tr>)}</tbody></table></div>
      <div className="lab-scroll"><table className="lab-history"><thead><tr><th>原请求 ID</th><th>结算状态</th><th>实际已扣积分</th></tr></thead><tbody>{data.calls.map(call=><tr key={call.requestId}><td className="break-all">{call.requestId}</td><td>{call.state}</td><td>{call.billedPoints??"待确认"}</td></tr>)}</tbody></table></div>
    </details>
  </section>;
}
