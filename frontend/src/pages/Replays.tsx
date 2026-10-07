import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { serverReplayApi } from "@/services/api";
import { useAuth } from "@/hooks/useAuth";
import { useQuery } from "@tanstack/react-query";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Database, PlayCircle } from "lucide-react";
import { closureText } from "@/i18n/closureTexts";
import { Input } from "@/components/ui/input";
import { DataState } from "@/components/DataState";

const Replays = () => {
  const { t, i18n } = useTranslation();
  const tr = (text: string) => closureText(i18n.language, text);
  const [scope, setScope] = useState("my");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [playerId, setPlayerId] = useState("");
  const [page, setPage] = useState(0);
  const { user } = useAuth();
  const serverArchives = useQuery({
    queryKey: ["server-replays", user?.id, scope, from, to, playerId, page],
    queryFn: () => {
      const params = { size: 20, page, from: from || undefined, to: to || undefined };
      return scope === "my" ? serverReplayApi.my(params) : serverReplayApi.list({ ...params, playerId: playerId || undefined });
    },
    retry: 1,
  });
  const archives = serverArchives.error ? [] : serverArchives.data?.items || [];

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t("replays.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("replays.subtitle")}</p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">{tr("回放范围")}<select aria-label={tr("回放范围")} className="ml-2 rounded border p-2" value={scope} onChange={e => { setScope(e.target.value); setPage(0); }}><option value="my">{tr("我的回放")}</option><option value="public">{tr("公开回放")}</option></select></label>
        <label className="text-sm">{tr("开始时间")}<Input type="datetime-local" value={from} onChange={e => { setFrom(e.target.value); setPage(0); }} /></label>
        <label className="text-sm">{tr("结束时间")}<Input type="datetime-local" value={to} onChange={e => { setTo(e.target.value); setPage(0); }} /></label>
        {scope === "public" && <label className="text-sm">{tr("参与者 ID")}<Input value={playerId} onChange={e => { setPlayerId(e.target.value); setPage(0); }} /></label>}
        <Button variant="outline" onClick={() => serverArchives.refetch()}>{tr("重试")}</Button>
      </div>
      {(serverArchives.isPending || serverArchives.error || !archives.length) && <DataState loading={serverArchives.isPending} error={serverArchives.error} empty={!archives.length} onRetry={() => void serverArchives.refetch()} />}
      {serverArchives.data && !serverArchives.error && <div className="flex items-center gap-3"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>{tr("上一页")}</Button><span>{page + 1}</span><Button disabled={(page + 1) * 20 >= (serverArchives.data?.total || 0)} onClick={() => setPage(p => p + 1)}>{tr("下一页")}</Button></div>}
      {!serverArchives.error && archives.length > 0 && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {archives.map((archive) => (
            <Card key={archive.id}>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">{archive.roomName}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="flex flex-wrap gap-2">
                  <Badge variant="secondary">{archive.gameId}</Badge>
                  <Badge variant="outline">{t("replays.winner", { winner: archive.winner || t("common.undetermined") })}</Badge>
                  {typeof archive.eventCount === "number" && <Badge variant="outline">{t("replays.events", { count: archive.eventCount })}</Badge>}
                </div>
                <div className="grid grid-cols-3 gap-2 rounded-md border bg-slate-50 p-2 text-center text-xs">
                  {typeof archive.playerCount === "number" && <div>
                    <div className="font-semibold">{archive.playerCount}</div>
                    <div className="text-muted-foreground">{t("replays.players")}</div>
                  </div>}
                  {typeof archive.totalRounds === "number" && <div>
                    <div className="font-semibold">{archive.totalRounds}</div>
                    <div className="text-muted-foreground">{t("replays.rounds")}</div>
                  </div>}
                  {typeof archive.aiQualitySummary?.traceCount === "number" && <div>
                    <div className="font-semibold">{archive.aiQualitySummary.traceCount}</div>
                    <div className="text-muted-foreground">AI Trace</div>
                  </div>}
                </div>
                <div className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Database className="h-3.5 w-3.5" />
                  {archive.finishedAt ? new Date(archive.finishedAt).toLocaleString() : t("replays.serverArchive")}
                </div>
                <Button asChild className="w-full">
                  <Link to={`/replay/${archive.id}`}>
                    <PlayCircle className="mr-2 h-4 w-4" />
                    {t("replays.play")}
                  </Link>
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
};

export default Replays;
