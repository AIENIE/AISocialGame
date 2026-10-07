import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { serverReplayApi } from "@/services/api";
import { useAuth } from "@/hooks/useAuth";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Pause, Play, Shield, SkipForward } from "lucide-react";
import { ReplayViewMode } from "@/types";
import { DataState } from "@/components/DataState";

const ReplayPlayer = () => {
  const { t } = useTranslation();
  const { archiveId } = useParams();
  const { user } = useAuth();
  const [viewMode, setViewMode] = useState<ReplayViewMode>("PUBLIC");
  const serverReplay = useQuery({
    queryKey: ["server-replay", archiveId, viewMode, user?.id],
    queryFn: () => serverReplayApi.events(archiveId || "", viewMode),
    enabled: !!archiveId,
    retry: 1,
  });
  const serverArchive = serverReplay.error ? undefined : serverReplay.data?.archive;
  const serverEvents = serverReplay.data?.events || [];
  const usingServer = !!serverArchive;
  const archive = usingServer
    ? {
        id: serverArchive.id,
        gameId: serverArchive.gameId,
        roomId: serverArchive.roomId,
        roomName: serverArchive.roomName,
        result: serverArchive.winner || t("common.undetermined"),
        createdAt: serverArchive.finishedAt || serverArchive.createdAt,
        events: serverEvents.map((event) => ({
          id: String(event.id),
          type: event.eventType,
          message: String(event.data?.message || event.data?.content || event.eventType),
          timestamp: event.occurredAt,
          phase: event.phase,
          roundNumber: event.roundNumber,
          seq: event.seq,
          visibility: event.visibility,
          data: event.data,
        })),
      }
    : undefined;
  const [index, setIndex] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);

  useEffect(() => {
    setIndex(0);
    setPlaying(false);
  }, [archiveId, viewMode]);

  useEffect(() => {
    if (!playing || !archive) return;
    const timer = window.setInterval(() => {
      setIndex((prev) => {
        if (prev >= archive.events.length - 1) {
          setPlaying(false);
          return prev;
        }
        return prev + 1;
      });
    }, Math.max(250, 1000 / speed));
    return () => window.clearInterval(timer);
  }, [playing, speed, archive?.id]);

  if (!archive) return <DataState loading={serverReplay.isPending} error={serverReplay.error} onRetry={() => void serverReplay.refetch()} />;

  const currentEvent: any = archive.events[index];

  return (
    <div className="mx-auto max-w-5xl space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-lg">{archive.roomName}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{archive.gameId}</Badge>
            <Badge variant="outline">{t("replays.result", { result: archive.result })}</Badge>
            {usingServer && <Badge variant="outline">{t("replays.serverArchive")}</Badge>}
            <Badge variant="outline">
              {archive.events.length ? index + 1 : 0}/{archive.events.length}
            </Badge>
          </div>
          {usingServer && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="flex items-center gap-1 text-sm text-muted-foreground">
                <Shield className="h-4 w-4" />
                {t("replay.view")}
              </span>
              {(serverReplay.data?.availableViews || ["PUBLIC"] as ReplayViewMode[]).map((mode) => (
                <Button key={mode} size="sm" variant={viewMode === mode ? "default" : "outline"} onClick={() => setViewMode(mode)}>
                  {t(`replay.view.${mode}`)}
                </Button>
              ))}
            </div>
          )}
          <div className="rounded-lg border bg-slate-50 p-4">
            <div className="mb-3 flex flex-wrap gap-2">
              {archive.events.map((event, eventIndex) => ({ event, eventIndex }))
                .filter(({ event, eventIndex }) => eventIndex === 0 || event.phase !== archive.events[eventIndex - 1].phase)
                .map(({ event, eventIndex }) => <Button key={eventIndex} size="sm" variant="outline" onClick={() => { setIndex(eventIndex); setPlaying(false); }}>{event.phase || event.type} #{event.seq || eventIndex + 1}</Button>)}
            </div>
            <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              {currentEvent?.timestamp && <span>{new Date(currentEvent.timestamp).toLocaleTimeString()}</span>}
              {currentEvent?.seq && <Badge variant="outline">#{currentEvent.seq}</Badge>}
              {currentEvent?.phase && <Badge variant="secondary">{currentEvent.phase}</Badge>}
              {currentEvent?.type && <Badge variant="outline">{currentEvent.type}</Badge>}
            </div>
            <div className="text-base font-medium">{currentEvent?.message || t("replay.noEvent")}</div>
            {currentEvent?.data?.aiTraceId && (
              <div className="mt-2 text-xs text-muted-foreground">
                AI Trace #{currentEvent.data.aiTraceId}
                {currentEvent.data.aiFallback ? " · fallback" : ""}
              </div>
            )}
          </div>
          <input
            type="range"
            className="w-full"
            min={0}
            max={Math.max(archive.events.length - 1, 0)}
            value={index}
            onChange={(event) => setIndex(Number(event.target.value))}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => setPlaying((v) => !v)} disabled={archive.events.length === 0}>
              {playing ? <Pause className="mr-2 h-4 w-4" /> : <Play className="mr-2 h-4 w-4" />}
              {playing ? t("replay.pause") : t("replay.play")}
            </Button>
            <Button variant="outline" disabled={archive.events.length === 0 || index >= archive.events.length - 1} onClick={() => setIndex((prev) => Math.min(prev + 1, archive.events.length - 1))}>
              <SkipForward className="mr-2 h-4 w-4" />
              {t("replay.step")}
            </Button>
            <div className="ml-auto flex items-center gap-2 text-sm">
              <span>{t("replay.speed")}</span>
              <Button size="sm" variant={speed === 1 ? "default" : "outline"} onClick={() => setSpeed(1)}>
                1x
              </Button>
              <Button size="sm" variant={speed === 2 ? "default" : "outline"} onClick={() => setSpeed(2)}>
                2x
              </Button>
              <Button size="sm" variant={speed === 4 ? "default" : "outline"} onClick={() => setSpeed(4)}>
                4x
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">{t("replay.timeline")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {archive.events.slice(0, index + 1).map((event, eventIndex) => (
            <div
              key={event.id}
              className={`rounded-md border px-3 py-2 text-sm ${eventIndex === index ? "border-blue-200 bg-blue-50" : "border-slate-200 bg-white"}`}
              onClick={() => setIndex(eventIndex)}
            >
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                {event.timestamp && <span>{new Date(event.timestamp).toLocaleTimeString()}</span>}
                {(event as any).seq && <span>#{(event as any).seq}</span>}
                {(event as any).phase && <span>{(event as any).phase}</span>}
                <span>{event.type}</span>
              </div>
              <div>{event.message}</div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
};

export default ReplayPlayer;
