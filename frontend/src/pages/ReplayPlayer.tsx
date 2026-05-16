import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { replayApi } from "@/services/v2Social";
import { serverReplayApi } from "@/services/api";
import { useAuth } from "@/hooks/useAuth";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Pause, Play, Shield, SkipForward } from "lucide-react";
import { ReplayViewMode } from "@/types";

const ReplayPlayer = () => {
  const { archiveId } = useParams();
  const { user, displayName } = useAuth();
  const userKey = useMemo(() => user?.id || `guest:${displayName}`, [user?.id, displayName]);
  const [viewMode, setViewMode] = useState<ReplayViewMode>("PUBLIC");
  const serverReplay = useQuery({
    queryKey: ["server-replay", archiveId, viewMode],
    queryFn: () => serverReplayApi.events(archiveId || "", viewMode),
    enabled: !!archiveId,
    retry: 1,
  });
  const localArchive = archiveId ? replayApi.get(userKey, archiveId) : undefined;
  const serverArchive = serverReplay.data?.archive;
  const serverEvents = serverReplay.data?.events || [];
  const usingServer = !!serverArchive && serverEvents.length > 0;
  const archive = usingServer
    ? {
        id: serverArchive.id,
        gameId: serverArchive.gameId,
        roomId: serverArchive.roomId,
        roomName: serverArchive.roomName,
        result: serverArchive.winner || "未判定",
        createdAt: serverArchive.finishedAt || serverArchive.createdAt || new Date().toISOString(),
        events: serverEvents.map((event) => ({
          id: String(event.id),
          type: event.eventType,
          message: String(event.data?.message || event.data?.content || event.eventType),
          timestamp: event.occurredAt || new Date().toISOString(),
          phase: event.phase,
          roundNumber: event.roundNumber,
          seq: event.seq,
          visibility: event.visibility,
          data: event.data,
        })),
      }
    : localArchive;
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

  if (!archive) {
    return (
      <Card className="mx-auto max-w-3xl">
        <CardContent className="py-10 text-center text-sm text-muted-foreground">
          {serverReplay.isLoading ? "正在读取服务端回放..." : "未找到回放数据，请先在“对局回放”列表中选择存档。"}
        </CardContent>
      </Card>
    );
  }

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
            <Badge variant="outline">结果: {archive.result}</Badge>
            {usingServer && <Badge variant="outline">服务端回放</Badge>}
            <Badge variant="outline">
              {index + 1}/{Math.max(archive.events.length, 1)}
            </Badge>
          </div>
          {usingServer && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="flex items-center gap-1 text-sm text-muted-foreground">
                <Shield className="h-4 w-4" />
                视角
              </span>
              {(["PUBLIC", "PLAYER", "GOD"] as ReplayViewMode[]).map((mode) => (
                <Button key={mode} size="sm" variant={viewMode === mode ? "default" : "outline"} onClick={() => setViewMode(mode)}>
                  {mode}
                </Button>
              ))}
            </div>
          )}
          <div className="rounded-lg border bg-slate-50 p-4">
            <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>{currentEvent?.timestamp ? new Date(currentEvent.timestamp).toLocaleTimeString() : "--:--:--"}</span>
              {currentEvent?.seq && <Badge variant="outline">#{currentEvent.seq}</Badge>}
              {currentEvent?.phase && <Badge variant="secondary">{currentEvent.phase}</Badge>}
              {currentEvent?.type && <Badge variant="outline">{currentEvent.type}</Badge>}
            </div>
            <div className="text-base font-medium">{currentEvent?.message || "暂无事件"}</div>
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
              {playing ? "暂停" : "播放"}
            </Button>
            <Button variant="outline" onClick={() => setIndex((prev) => Math.min(prev + 1, archive.events.length - 1))}>
              <SkipForward className="mr-2 h-4 w-4" />
              单步
            </Button>
            <div className="ml-auto flex items-center gap-2 text-sm">
              <span>速度</span>
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
          <CardTitle className="text-base">事件时间线</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {archive.events.map((event, eventIndex) => (
            <div
              key={event.id}
              className={`rounded-md border px-3 py-2 text-sm ${eventIndex === index ? "border-blue-200 bg-blue-50" : "border-slate-200 bg-white"}`}
              onClick={() => setIndex(eventIndex)}
            >
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span>{new Date(event.timestamp).toLocaleTimeString()}</span>
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
