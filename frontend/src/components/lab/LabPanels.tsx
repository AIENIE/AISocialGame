import { Link } from "react-router-dom";
import { ArrowUpRight, Coins, Radio, FlaskConical } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { LabCost, LabEvaluation, LabExperiment, LabMatch, LabPage } from "@/types/lab";
import "./lab.css";
import { failureText } from "./labFailures";
import { labText } from "@/i18n/labTexts";

export type LabTranslate = (text: string) => string;
const plain: LabTranslate = text => labText("zh-CN",text);
const money = (points: number) => (points / 100).toFixed(2);
const percent = (value: number) => (value * 100).toFixed(1) + "%";
export function LabState({ state, tr = plain }: { state: string; tr?: LabTranslate }) {
  return <span className="lab-state" data-state={state}>{tr(state)}</span>;
}
export function LabCostPanel({ cost, limit, tr = plain }: { cost: LabCost; limit: number; tr?: LabTranslate }) {
  return <aside className="lab-sidebar"><h2 className="flex items-center gap-2"><Coins size={16} />{tr("实验费用")}</h2>
    <div className="lab-ledger"><span>{tr("费用上限")}</span><strong>¥{money(limit)}</strong></div>
    <div className="lab-ledger"><span>{tr("已扣积分折合")}</span><strong>¥{money(cost.spentPoints)}</strong></div>
    <div className="lab-ledger"><span>{tr("未结预留")}</span><strong>¥{money(cost.heldPoints)}</strong></div>
    <div className="lab-ledger"><span>{tr("待对账金额（已含于预留）")}</span><strong>¥{money(cost.pendingPoints)}</strong></div>
    <div className="lab-ledger border-t"><span>{tr("剩余可预留")}</span><strong>¥{money(Math.max(0, limit-cost.spentPoints-cost.heldPoints))}</strong></div>
    <div className="lab-small mt-5">{tr("供应商费用")} ¥{Number(cost.actualCost).toFixed(6)}<br />{cost.totalCalls} {tr("笔调用")} · {cost.pendingCalls} {tr("笔待对账")}</div>
    <p className="lab-small mt-4">{tr("对局、修正、复盘与评测共用此额度。未知费用保留预留，等待对账。")}</p></aside>;
}
export function LabProgress({ experiment, tr = plain }: { experiment: LabExperiment; tr?: LabTranslate }) {
  const trained = Math.min(experiment.configuration?.trainingCreated ?? experiment.attemptedGames, experiment.targetGames);
  return <div className="lab-hero"><div><div className="lab-eyebrow">{tr("对局尝试")}</div><div className="lab-big-number">{trained}<small>/ {experiment.targetGames}</small></div></div>
    <div className="lab-progress"><div className="flex justify-between gap-4 text-xs"><span>{tr("已处理至")} #{experiment.reviewedThrough}</span><LabState state={experiment.status} tr={tr} /></div>
      <div className="lab-track"><span style={{ width: percent(Math.min(1,trained/experiment.targetGames)) }} /></div>
      <div className="mt-3 text-xs text-emerald-100/70">{experiment.playerCount} {tr("席")} · {tr("逐局学习")} · {tr("谁是卧底")}</div>
      {experiment.completedGames!==undefined&&<div className="mt-2 text-xs text-emerald-100/90">{tr("正常结束")} {experiment.completedGames} · {tr("复盘成功")} {experiment.reviewedGames} · {tr("中断")} {experiment.abortedGames}</div>}</div></div>;
}
export function LabExperimentRows({ rows, admin = false, tr = plain }: { rows: LabExperiment[]; admin?: boolean; tr?: LabTranslate }) {
  return <div className="lab-list">{rows.length ? rows.map(experiment => <Link className="lab-list-row" key={experiment.id} to={(admin?"/admin/lab/":"/lab/")+experiment.id}>
    <div><h3>{experiment.name}</h3><p className="lab-small mt-1">{tr("谁是卧底")} · {experiment.playerCount} {tr("席")} · {tr("逐局学习")}</p>{experiment.reason&&<p className="lab-list-issue">{tr(failureText(experiment.reason))}</p>}</div>
    <div><LabState state={experiment.status} tr={tr} /><div className="lab-small mt-2 lab-number">{experiment.attemptedGames} / {experiment.targetGames} {tr("局")}</div></div>
    <div className="lab-list-cost lab-small">{tr("已扣积分折合")}<br /><span className="lab-number">¥{money(experiment.cost.spentPoints)}</span></div>
    <ArrowUpRight size={18} className="text-emerald-700" /></Link>) : <div className="lab-empty"><FlaskConical className="mx-auto mb-3 opacity-40" size={28} />{tr("还没有实验")}</div>}</div>;
}
export function LabLiveMatches({ matches, participantMatchId, adminExperimentId, tr = plain }: { matches: LabMatch[]; participantMatchId?: string; adminExperimentId?: string; tr?: LabTranslate }) {
  return <section className="lab-section"><h2><Radio size={16} />{tr("正在进行")}</h2><div className="lab-live">
    {matches.length ? matches.map(match => <article className="lab-table" key={match.id}><LabState state={match.status} tr={tr} /><strong>{tr("对局")} #{match.sequenceNumber}</strong>
      <Button asChild size="sm" variant="outline"><Link to={adminExperimentId?`/admin/lab/${adminExperimentId}/matches/${match.id}`:participantMatchId===match.id ? "/room/undercover/"+match.roomId : "/spectate/undercover/"+match.roomId}>{tr(participantMatchId===match.id?"进入对局":"观战")}<ArrowUpRight className="ml-2" size={14} /></Link></Button></article>) : <p className="lab-small">{tr("当前没有运行中的对局")}</p>}
  </div></section>;
}
export function LabMatchHistory({ page, admin = false, tr = plain, onInspect }: { page: LabPage<LabMatch>; admin?: boolean; tr?: LabTranslate; onInspect?:(matchId:string)=>void }) {
  return <div className="lab-scroll"><table className="lab-history"><thead><tr><th>#</th><th>{tr("状态")}</th><th>{tr("样本")}</th><th>{tr("获胜 AI")}</th><th>{tr("回放")}</th></tr></thead>
    <tbody>{page.items.map(match => <tr key={match.id}><td className="lab-number">{match.sequenceNumber}</td><td><LabState state={match.status} tr={tr} />{match.reason && <div className="lab-small mt-1">{tr(failureText(match.reason))}</div>}</td>
      <td>{tr(match.variant || "训练")}</td><td>{match.results?.filter(player=>player.ai&&player.won).map(player=>player.name).join(" / ") || "—"}</td>
      <td>{match.archiveId ? <Link className="text-emerald-700 underline underline-offset-4" to={(admin?"/admin/replays/":"/replay/")+match.archiveId}>{tr("查看")}</Link> : "—"}{admin&&onInspect&&<button className="ml-3 underline text-emerald-700" onClick={()=>onInspect(match.id)}>诊断</button>}</td></tr>)}</tbody></table>
    {!page.items.length && <div className="lab-empty">{tr("对局记录将在运行后出现")}</div>}</div>;
}
export function LabEvaluationResults({ evaluations, tr = plain }: { evaluations: LabEvaluation[]; tr?: LabTranslate }) {
  return <section className="lab-section"><h2>{tr("对照评测")}</h2><p className="lab-small mb-4">{tr("固定对手，成对复用局面；仅完整的纯 AI 对照样本计入结果。差异不保证代表能力提升。")}</p>
    {!evaluations.length && <p className="lab-small">{tr("尚未运行对照评测")}</p>}
    {evaluations.map(evaluation=><article className="lab-table mb-3" key={evaluation.id}><div className="flex flex-wrap items-center justify-between gap-3"><LabState state={evaluation.status} tr={tr} /><span className="lab-small">{evaluation.validPairs} / {evaluation.pairs} {tr("组成对样本")}</span></div>
      <div className="lab-scroll mt-3"><table className="lab-history"><thead><tr><th>{tr("指标")}</th><th>{tr("初始经验")}</th><th>{tr("训练后经验")}</th></tr></thead><tbody>
        <tr><td>{tr("胜率")}</td><td>{percent(evaluation.baseline.winRate)}</td><td>{percent(evaluation.candidate.winRate)}</td></tr>
        <tr><td>{tr("已结束尝试 / 失败")}</td><td>{evaluation.baseline.attemptedGames} / {evaluation.baseline.failedGames}</td><td>{evaluation.candidate.attemptedGames} / {evaluation.candidate.failedGames}</td></tr>
        <tr><td>{tr("有效行动率")}</td><td>{percent(evaluation.baseline.validActionRate)}</td><td>{percent(evaluation.candidate.validActionRate)}</td></tr>
        <tr><td>{tr("失败率")}</td><td>{percent(evaluation.baseline.failureRate)}</td><td>{percent(evaluation.candidate.failureRate)}</td></tr>
        {["CIVILIAN","UNDERCOVER","BLANK"].map(role=><tr key={role}><td>{tr(role)}</td><td>{percent(evaluation.baseline.roles[role]?.winRate ?? 0)} · {evaluation.baseline.roles[role]?.wins ?? 0} / {evaluation.baseline.roles[role]?.games ?? 0}</td><td>{percent(evaluation.candidate.roles[role]?.winRate ?? 0)} · {evaluation.candidate.roles[role]?.wins ?? 0} / {evaluation.candidate.roles[role]?.games ?? 0}</td></tr>)}</tbody></table></div>
      <p className="lab-small mt-3">{tr("胜率仅含完整配对；有效行动率包含中断局的已执行行动，失败率按已结束尝试计算。")}</p>
      <p className="lab-small mt-3">{tr("胜率差异")} <span className="lab-number">{evaluation.winRateDifference>=0?"+":""}{percent(evaluation.winRateDifference)}</span></p></article>)}</section>;
}
export function LabPager({ page, total, size, change, tr = plain }: { page: number; total: number; size: number; change: (page: number)=>void; tr?: LabTranslate }) {
  return <div className="lab-pager"><Button size="sm" variant="outline" disabled={page===0} onClick={()=>change(page-1)}>{tr("上一页")}</Button><span className="lab-number">{page+1}</span><Button size="sm" variant="outline" disabled={(page+1)*size>=total} onClick={()=>change(page+1)}>{tr("下一页")}</Button></div>;
}
