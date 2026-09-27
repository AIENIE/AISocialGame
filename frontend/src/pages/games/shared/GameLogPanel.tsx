import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { gameplayApi } from "@/services/api";
import { GameLogEntry } from "@/types";
import { presentationText } from "./aiPresentation";

interface GameLogPanelProps {
  gameId?: string;
  roomId?: string;
  archiveId?: string;
  viewVersion?: string;
  logs?: GameLogEntry[];
  emptyText: string;
}

const ESTIMATED_ROW_HEIGHT = 48;
const ROW_GAP = 8;
const VIEWPORT_HEIGHT = 256;
const OVERSCAN = 128;

function logKey(log: GameLogEntry, index: number) {
  return String(log.metadata?.eventId || `${log.time}:${log.message}:${index}`);
}

function VirtualLogRow({ log, itemKey, top, onMeasure, language }: {
  log: GameLogEntry;
  itemKey: string;
  top: number;
  onMeasure: (itemKey: string, height: number) => void;
  language: string;
}) {
  const element = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = element.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      const height = node.getBoundingClientRect().height;
      if (height > 0) onMeasure(itemKey, height);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [itemKey, onMeasure]);
  const cue = presentationText(log.metadata?.presentation, language);
  const date = new Date(log.time);
  return (
    <div ref={element} role="listitem" data-testid="game-log-item"
      className="absolute left-0 right-0 flex items-start gap-3 text-sm"
      style={{ top }}>
      <time dateTime={log.time} className="shrink-0 pt-0.5 text-xs tabular-nums text-muted-foreground">
        {Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString(language, { hour: "2-digit", minute: "2-digit" })}
      </time>
      <div className="min-w-0 flex-1">
        <span className="whitespace-pre-wrap break-words">{log.message}</span>
        {cue && <span data-testid="ai-presentation" className="ml-2 text-xs italic text-muted-foreground motion-reduce:transition-none">· {cue}</span>}
      </div>
    </div>
  );
}

function VirtualLogList({ logs, emptyText, language, pageKey, label }: {
  logs: GameLogEntry[];
  emptyText: string;
  language: string;
  pageKey: string;
  label: string;
}) {
  const scroll = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [heights, setHeights] = useState<Record<string, number>>({});
  useEffect(() => {
    setHeights({});
    setScrollTop(0);
    if (scroll.current) scroll.current.scrollTop = 0;
  }, [pageKey]);
  const onMeasure = useCallback((itemKey: string, height: number) => setHeights(previous =>
    Math.abs((previous[itemKey] ?? ESTIMATED_ROW_HEIGHT) - height) < 1 ? previous : { ...previous, [itemKey]: height }), []);
  const layout = useMemo(() => {
    const tops: number[] = [];
    const keys: string[] = [];
    let total = 0;
    for (let index = 0; index < logs.length; index++) {
      const key = logKey(logs[index], index);
      keys.push(key);
      tops.push(total);
      total += (heights[key] ?? ESTIMATED_ROW_HEIGHT) + ROW_GAP;
    }
    return { tops, keys, total };
  }, [heights, logs]);
  const first = layout.tops.findIndex((top, index) => top + (heights[layout.keys[index]] ?? ESTIMATED_ROW_HEIGHT) >= scrollTop - OVERSCAN);
  const from = first < 0 ? logs.length : first;
  let to = from;
  while (to < logs.length && layout.tops[to] <= scrollTop + VIEWPORT_HEIGHT + OVERSCAN) to++;
  return (
    <div ref={scroll} data-testid="game-logs-scroll" className="h-64 overflow-y-auto pr-2"
      role="region" aria-label={label} tabIndex={0}
      onScroll={event => setScrollTop(event.currentTarget.scrollTop)}>
      {logs.length === 0 ? <div data-testid="game-log-empty" className="text-sm text-muted-foreground">{emptyText}</div> :
        <div className="relative" role="list" aria-label={label} style={{ height: layout.total }}>
          {logs.slice(from, to).map((log, offset) => {
            const index = from + offset;
            return <VirtualLogRow key={layout.keys[index]} log={log} itemKey={layout.keys[index]}
              top={layout.tops[index]} onMeasure={onMeasure} language={language} />;
          })}
        </div>}
    </div>
  );
}

export function GameLogPanel({ gameId, roomId, archiveId, viewVersion, logs = [], emptyText }: GameLogPanelProps) {
  const { t, i18n } = useTranslation();
  const [cursors, setCursors] = useState<(number | null)[]>([null]);
  const cursor = cursors.at(-1) ?? null;
  useEffect(() => setCursors([null]), [archiveId, gameId, roomId]);
  const page = useQuery({
    queryKey: ["game-logs", gameId, roomId, archiveId, cursor, cursor === null ? viewVersion : null],
    queryFn: () => gameplayApi.logs(gameId || "", roomId || "", cursor ?? undefined),
    enabled: !!gameId && !!roomId,
  });
  const visible = (cursor === null ? page.data?.items ?? logs.slice(-100) : page.data?.items ?? []);
  return (
    <Card className="p-4" data-testid="game-logs-panel">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="font-semibold">{t("game.logTitle")}</h3>
        <div className="flex items-center gap-2">
          {cursor === null && <Badge variant="outline">{t("game.live")}</Badge>}
          <Button size="sm" variant="outline" disabled={!page.data?.hasMore || page.isFetching}
            onClick={() => setCursors(previous => [...previous, page.data?.nextCursor ?? null])}>{t("wallet.prev")}</Button>
          <Button size="sm" variant="outline" disabled={cursors.length < 2 || page.isFetching}
            onClick={() => setCursors(previous => previous.slice(0, -1))}>{t("wallet.next")}</Button>
        </div>
      </div>
      <VirtualLogList logs={visible} emptyText={emptyText} language={i18n.language} label={t("game.logTitle")}
        pageKey={`${archiveId || ""}:${cursor ?? "latest"}`} />
    </Card>
  );
}
