import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { FlaskConical, ArrowLeft, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { labApi, getApiErrorMessage } from "@/services/api";
import { failureText } from "@/components/lab/labFailures";
import { Button } from "@/components/ui/button";
import { DataState } from "@/components/DataState";
import { BrandLogo } from "@/components/brand/BrandLogo";
import { LabExperimentRows, LabCostPanel, LabProgress, LabLiveMatches, LabMatchHistory, LabEvaluationResults, LabPager } from "@/components/lab/LabPanels";
import { labText } from "@/i18n/labTexts";

function LabIndex() {
  const { i18n } = useTranslation();
  const tr = (text: string) => labText(i18n.language,text);
  const [page,setPage] = useState(0);
  const query = useQuery({ queryKey:["lab","list",page], queryFn:()=>labApi.list(page), refetchInterval:5000 });
  return <div className="lab-workspace">
    <header className="lab-heading"><div><div className="lab-eyebrow"><BrandLogo compact /> / RESEARCH</div><h1>{tr("AI 实验室")}</h1><p>{tr("让每一局，成为下一局的经验。")}<br />{tr("观察 AI 的连续对局，参与实验，见证策略如何变化。")}</p></div><FlaskConical size={44} strokeWidth={1} className="text-emerald-700/50" /></header>
    <p className="lab-small mb-6">{tr("管理员管理实验；真人参与使用现有游戏规则。")}</p>
    {query.isPending || query.error ? <DataState loading={query.isPending} error={query.error} onRetry={()=>void query.refetch()} /> : <>
      <LabExperimentRows rows={query.data?.items ?? []} tr={tr} /><LabPager page={page} total={query.data?.total ?? 0} size={20} change={setPage} tr={tr} /></>}
  </div>;
}
function LabDetail({id}:{id:string}) {
  const { i18n } = useTranslation();
  const tr = (text:string)=>labText(i18n.language,text);
  const client = useQueryClient();
  const [page,setPage] = useState(0);
  const query = useQuery({queryKey:["lab","experiment",id],queryFn:()=>labApi.detail(id),refetchInterval:2000});
  const history = useQuery({queryKey:["lab","matches",id,page],queryFn:()=>labApi.matches(id,page),refetchInterval:5000});
  const action = useMutation({
    mutationFn:(kind:"enroll"|"confirm"|"withdraw")=>labApi[kind](id),
    onSuccess:()=>{ void client.invalidateQueries({queryKey:["lab","experiment",id]}); toast.success(tr("报名状态已更新")); },
    onError:error=>toast.error(getApiErrorMessage(error,tr("操作失败，请重试"))),
  });
  if(!query.data) return <DataState loading={query.isPending} error={query.error} onRetry={()=>void query.refetch()} />;
  const experiment=query.data;
  const enrollment=experiment.enrollment;
  const active=enrollment?.status;
  const remaining=Math.max(0,Math.ceil(((enrollment?.confirmUntil ?? 0)-Date.now())/1000));
  const participating=["WAITING","OFFERED","CONFIRMED","PLAYING"].includes(active ?? "");
  const canEnroll=["DRAFT","RUNNING","PAUSING","PAUSED"].includes(experiment.status) && !experiment.evaluations?.some(evaluation=>evaluation.status==="RUNNING");
  return <div className="lab-workspace">
    <Link to="/lab" className="lab-small mb-5 inline-flex items-center gap-2"><ArrowLeft size={14} />{tr("返回实验室")}</Link>
    <header className="lab-heading"><div><div className="lab-eyebrow"><BrandLogo compact /> / RESEARCH</div><h1>{experiment.name}</h1><p>{tr(experiment.gameId==="werewolf"?"狼人杀":"谁是卧底")} · {tr("AI 实验室")}</p></div></header>
    <LabProgress experiment={experiment} tr={tr} />
    {experiment.reason && <div className="lab-error" role="status">{tr(experiment.status)} · {tr(failureText(experiment.reason))}</div>}
    <div className="lab-columns mt-7"><div>
      <div className="lab-enroll"><h2 className="flex items-center gap-2 text-sm font-semibold"><UserPlus size={16} />{tr("参战下一局")}</h2>
        <p className="lab-small mt-2 mb-4">{tr("先报名，在下一局开场前确认席位；空位由 AI 自动补齐。")}</p>
        {active==="WAITING" && <p className="text-sm mb-3">{tr("等待下一局确认窗口")}</p>}
        {active==="OFFERED" && <div className="mb-3"><p className="text-sm">{tr("请在倒计时结束前确认")} <strong className="lab-number">{remaining}s</strong></p><Button className="mt-3" disabled={action.isPending || remaining===0} onClick={()=>action.mutate("confirm")}>{tr("确认参战")}</Button></div>}
        {active==="CONFIRMED" && <p className="text-sm mb-3">{tr("席位已确认，等待开局")}</p>}
        {active==="PLAYING" && <p className="text-sm mb-3">{tr("正在参战，本局结束后可再次报名")}</p>}
        {!participating && <Button disabled={!canEnroll || action.isPending} onClick={()=>action.mutate("enroll")}>{tr("报名下一局")}</Button>}
        {participating && active!=="PLAYING" && <Button variant="outline" disabled={action.isPending} onClick={()=>action.mutate("withdraw")}>{tr("取消报名")}</Button>}
      </div>
      <LabLiveMatches gameId={experiment.gameId} matches={experiment.activeMatches ?? []} participantMatchId={active==="PLAYING"?enrollment?.matchId:undefined} tr={tr} />
      <LabEvaluationResults gameId={experiment.gameId} evaluations={experiment.evaluations ?? []} tr={tr} />
    </div><LabCostPanel cost={experiment.cost} limit={experiment.budgetPoints} tr={tr} /></div>
    <section className="lab-section"><h2>{tr("对局历史")}</h2>{history.data ? <><LabMatchHistory page={history.data} tr={tr} /><LabPager page={page} total={history.data.total} size={20} change={setPage} tr={tr} /></> : <DataState loading={history.isPending} error={history.error} onRetry={()=>void history.refetch()} />}</section>
  </div>;
}
export default function Lab() {
  const {id}=useParams();
  return id ? <LabDetail key={id} id={id} /> : <LabIndex />;
}
