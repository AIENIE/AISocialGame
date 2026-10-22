import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { AlertTriangle, ChevronDown } from "lucide-react";
import { adminLabApi } from "@/services/api";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { failureText } from "./labFailures";

export function LabDiagnostics({experimentId,matchId}:{experimentId:string;matchId:string}) {
  const query=useQuery({queryKey:["admin","lab","diagnostics",experimentId,matchId],queryFn:({signal})=>adminLabApi.diagnostics(experimentId,matchId,signal)});
  if(!query.data) return <AdminDataState loading={query.isPending} error={query.error} retry={()=>void query.refetch()}/>;
  const data=query.data,issue=data.failure;
  const spent=data.calls.reduce((total,call)=>total+call.billedPoints,0);
  const held=data.calls.reduce((total,call)=>total+call.heldPoints,0);
  return <section className="lab-diagnostics" id="lab-diagnostics" aria-label="对局诊断">
    <div className="flex items-center gap-2"><AlertTriangle size={20}/><h2>{issue?failureText(issue.code):"对局诊断"} · 第 {data.sequenceNumber} 局</h2></div>
    {issue&&<><p className="mt-3">{issue.code==="LEGACY_WORD_SCOPE_REJECTION"?"旧版规则错误地拦截了白板或对方词语，并非模型调用失败。新版只限制本人词语；本历史局不重写，恢复后从下一局继续。":issue.code==="FORBIDDEN_WORD_ELIMINATED"?"角色三次提交命中本人禁词，已按规则出局；这不是实验故障，对局可以继续。":issue.code==="LAB_BUDGET_EXHAUSTED"?"剩余额度不足以预留下一次调用；预留上界不等于实耗。请核对预算，不要反复恢复。":issue.code==="BUDGET_RECONCILIATION_REQUIRED"?"请求结果尚未确认，原预留仍保留。等待原请求对账，不重新发送。":issue.stage==="REVIEW"?"本局已结束，但复盘未成功，旧经验仍保留。处理原因后使用“重试复盘并继续”。":"请查看下方动作和调用信息；原因缺失时不会推测为余额或模型故障。"}{issue.inferred&&"（依据已保存历史动作及当时局面还原。）"}</p>
      <dl className="lab-diagnostic-facts"><div><dt>发生位置</dt><dd>第 {issue.round||"—"} 轮 · {issue.phase||"—"}</dd></div><div><dt>涉及副本</dt><dd>{issue.actorName||"未记录"}</dd></div><div><dt>模型</dt><dd>{issue.modelKey||"未记录"}</dd></div><div><dt>失败阶段</dt><dd>{({SUBMISSION:"动作提交",GENERATION:"生成或校验",REVIEW:"教练复盘",EXECUTION:"执行",RULE:"游戏规则判罚"})[issue.stage]||issue.stage}</dd></div></dl>
      {issue.action&&<div className="lab-failed-action"><strong>被拒绝的动作 · {issue.action.type}</strong><p>{issue.action.content}</p></div>}
      {!issue.action&&<p className="lab-small mt-3">当时未保存可用的失败动作；仅显示已记录的原因，不推测原文。</p>}
    </>}
    <p className="mt-3 text-sm">本局已扣 <strong>{spent} 积分</strong>（¥{(spent/100).toFixed(2)}） · 未结预留 <strong>{held} 积分</strong>。已返回但提交失败的调用仍按实际用量结算。</p>
    <div className="lab-actions mt-3">{data.archiveId&&<Link className="underline" to={"/admin/replays/"+data.archiveId}>查看本局完整回放</Link>}</div>
    <details className="mt-4"><summary className="flex cursor-pointer items-center gap-2 text-sm"><ChevronDown size={14}/>技术详情与原请求账务</summary>
      {issue&&<p className="lab-small mt-3 break-all">原因码：{issue.code} · 任务：{issue.jobId||"未记录"}</p>}
      <div className="lab-scroll"><table className="lab-history"><thead><tr><th>AI 副本 / 模型</th><th>状态 / 原因</th><th>调用尝试</th></tr></thead><tbody>{data.tasks.map(task=><tr key={task.jobId}><td>{task.actorName}<br/>{task.modelKey}</td><td>{task.status}<br/>{failureText(task.reason)}</td><td>{task.attempts.map(attempt=><div key={attempt.attempt}>{attempt.attempt+1} · {attempt.flags?.join(" / ")||"通过生成校验"} {attempt.failureStage} {attempt.settlementState}</div>)}</td></tr>)}</tbody></table></div>
      <div className="lab-scroll"><table className="lab-history"><thead><tr><th>原请求 ID</th><th>结算状态</th><th>已扣 / 预留积分</th></tr></thead><tbody>{data.calls.map(call=><tr key={call.requestId}><td className="break-all">{call.requestId}</td><td>{call.state}</td><td>{call.billedPoints} / {call.heldPoints}</td></tr>)}</tbody></table></div>
    </details>
  </section>;
}
