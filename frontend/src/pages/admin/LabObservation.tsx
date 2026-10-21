import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { adminLabApi } from "@/services/api";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { LabState } from "@/components/lab/LabPanels";
import { AdminReplayEvidence } from "./AdminReplayEvidence";

export function AdminLabObservation() {
  const {id="",matchId=""}=useParams();
  const query=useQuery({queryKey:["admin","lab","observation",id,matchId],queryFn:({signal})=>adminLabApi.observe(id,matchId,signal),refetchInterval:2000,retry:1});
  const data=query.error?undefined:query.data;
  return <div className="lab-workspace space-y-5">
    <Link className="lab-small underline" to={`/admin/lab/${id}`}>返回实验详情</Link>
    <header className="lab-heading"><div><div className="lab-eyebrow">LAB / LIVE OBSERVATION</div><h1>实验只读观战</h1><p>保持管理身份，仅显示公开观战视图，不占用玩家席位。</p></div></header>
    {!data?<AdminDataState loading={query.isPending} error={query.error} retry={()=>void query.refetch()}/>:<>
      <div className="lab-actions"><strong>{data.room.name}</strong><LabState state={data.match.status}/><span className="lab-small">{data.state?.phase??"已归档"} · 第 {data.state?.round??0} 轮</span>{data.match.archiveId&&<Link className="text-emerald-700 underline" to={`/admin/replays/${data.match.archiveId}`}>管理端完整回放</Link>}</div>
      <div className="lab-form-grid">{data.state?.players.map(player=><article className="lab-table" key={player.playerId}><div className="lab-eyebrow">SEAT {player.seatNumber+1}</div><h2 className="mt-2 font-semibold">{player.displayName}</h2><p className="lab-small">{player.alive?"在场":"已出局"}{player.role?` · ${player.role}`:""}</p></article>)}</div>
      <section className="lab-section"><h2>公开对局记录</h2><ol className="space-y-3">{data.state?.logs.map((entry,index)=><li key={`${entry.time}:${index}`} className="lab-table break-words"><span className="lab-small mr-3">{entry.phase??entry.type}</span>{entry.message}</li>)}</ol></section>
    </>}
  </div>;
}

export function AdminReplayPage() {
  const {archiveId=""}=useParams();
  return <div className="space-y-5"><Link className="text-sm underline" to="/admin/lab">返回管理实验室</Link><h1 className="text-xl font-semibold">管理端完整回放</h1><AdminReplayEvidence key={archiveId} archiveId={archiveId} autoLoad/></div>;
}
