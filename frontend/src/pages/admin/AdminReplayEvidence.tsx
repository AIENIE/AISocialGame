import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/services/api";
import { Button } from "@/components/ui/button";

export function AdminReplayEvidence({ archiveId, eventIds = [], autoLoad = false }: { archiveId: string; eventIds?: string[]; autoLoad?: boolean }) {
  const tr = (s: string) => s;
  const [requested,setRequested]=useState(autoLoad);
  const query=useQuery({queryKey:["admin","replay",archiveId],queryFn:()=>adminApi.replayEvents(archiveId),enabled:requested,retry:1});
  const loading=query.isFetching,error=query.error,data=error?undefined:query.data;
  const load=()=>{ if(requested) void query.refetch(); else setRequested(true); };
  return <div className="space-y-2">
    <Button variant="outline" disabled={loading} onClick={load}>{loading ? tr("正在读取回放") : tr("管理端完整回放")}</Button>
    {error && <p role="alert">{tr("回放读取失败，请确认管理权限后重试。")}</p>}
    {data && <details open><summary>{tr("仅管理端可见的事件证据")} · {data.archive?.roomName??archiveId}</summary>
      <ol className="max-h-96 space-y-2 overflow-auto p-3">{data.events.map(event => <li key={event.id} className={eventIds.includes(String(event.data?.eventId)) ? "rounded border border-blue-400 p-2" : "p-2"}>
        <span>#{event.seq} · {event.phase} · {event.visibility} · {String(event.data?.eventId || event.id)}</span>
        <pre className="whitespace-pre-wrap break-all text-xs">{JSON.stringify(event.data, null, 2)}</pre>
      </li>)}</ol>
    </details>}
  </div>;
}
