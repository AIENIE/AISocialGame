import { useState } from "react";
import { useTranslation } from "react-i18next";
import { adminApi } from "@/services/api";
import { closureText } from "@/i18n/closureTexts";
import type { ReplayDetail } from "@/types";
import { Button } from "@/components/ui/button";

export function AdminReplayEvidence({ archiveId, eventIds = [] }: { archiveId: string; eventIds?: string[] }) {
  const { i18n } = useTranslation();
  const tr = (s: string) => closureText(i18n.language, s);
  const [data, setData] = useState<ReplayDetail>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  async function load() {
    if (loading) return;
    setLoading(true); setError(false); setData(undefined);
    try { setData(await adminApi.replayEvents(archiveId)); }
    catch { setError(true); }
    finally { setLoading(false); }
  }
  return <div className="space-y-2">
    <Button variant="outline" disabled={loading} onClick={load}>{loading ? tr("正在读取回放") : tr("管理端完整回放")}</Button>
    {error && <p role="alert">{tr("回放读取失败，请确认管理权限后重试。")}</p>}
    {data && <details open><summary>{tr("仅管理端可见的事件证据")} · {archiveId}</summary>
      <ol className="max-h-96 space-y-2 overflow-auto p-3">{data.events.map(event => <li key={event.id} className={eventIds.includes(String(event.data?.eventId)) ? "rounded border border-blue-400 p-2" : "p-2"}>
        <span>#{event.seq} · {event.phase} · {event.visibility} · {String(event.data?.eventId || event.id)}</span>
        <pre className="whitespace-pre-wrap break-all text-xs">{JSON.stringify(event.data, null, 2)}</pre>
      </li>)}</ol>
    </details>}
  </div>;
}
