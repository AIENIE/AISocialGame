import { useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowRight, LoaderCircle } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import type { GameLogEntry, GamePlayerStateView } from "@/types";
import { presentationText } from "./aiPresentation";
import { eventContent, eventKey, isHostEvent, isSpeechEvent } from "./roomEvents";
import { useRoomText } from "./roomText";

interface Props {
  logs: GameLogEntry[];
  players: GamePlayerStateView[];
  myPlayerId?: string;
  history?: boolean;
  hasMore?: boolean;
  loading?: boolean;
  error?: boolean;
  onLoadOlder: () => unknown;
  onRetry: () => unknown;
}

export function GameTimeline({ logs, players, myPlayerId, history = false, hasMore, loading, error, onLoadOlder, onRetry }: Props) {
  const { i18n } = useTranslation();
  const copy = useRoomText();
  const viewport = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const follow = useRef(!history);
  const previous = useRef<{ height: number; first?: string; last?: string }>();
  const [unread, setUnread] = useState(false);
  const first = logs[0] && eventKey(logs[0]), last = logs.at(-1) && eventKey(logs.at(-1)!);
  useLayoutEffect(() => {
    const node = viewport.current;
    if (!node) return;
    const prior = previous.current;
    if (prior && first !== prior.first && last === prior.last) node.scrollTop += node.scrollHeight - prior.height;
    else if (follow.current) node.scrollTop = node.scrollHeight;
    else if (prior && last !== prior.last) setUnread(true);
    previous.current = { height: node.scrollHeight, first, last };
  }, [first, last, logs.length]);
  useLayoutEffect(() => {
    if (!content.current || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      if (follow.current && viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
    });
    observer.observe(content.current);
    return () => observer.disconnect();
  }, []);
  const related = new Map<string, GameLogEntry>();
  logs.forEach(log => { if (log.metadata?.correlationId && !related.has(log.metadata.correlationId)) related.set(log.metadata.correlationId, log); });
  return <div className="room-timeline-wrap">
    <div ref={viewport} data-testid={history ? "room-history-scroll" : "game-timeline"} className="room-timeline" role="region" aria-label={copy(history ? "history" : "live")} tabIndex={0}
      onScroll={() => {
        const node = viewport.current!;
        follow.current = node.scrollHeight - node.scrollTop - node.clientHeight < 64;
        if (follow.current) setUnread(false);
      }}>
      <div ref={content} className="room-timeline-content">
        {hasMore && <Button variant="ghost" size="sm" className="mx-auto flex" disabled={loading} onClick={() => void onLoadOlder()}>{copy("older")}</Button>}
        {error && <div role="status" className="flex items-center justify-center gap-2 text-xs text-muted-foreground">{copy("historyError")}<Button variant="ghost" size="sm" onClick={() => void onRetry()}>{copy("retry")}</Button></div>}
        {loading && <LoaderCircle className="mx-auto h-4 w-4 animate-spin motion-reduce:animate-none" aria-label={copy("loading")} />}
        {logs.length === 0 && <p className="m-auto py-12 text-center text-sm text-muted-foreground">{copy("empty")}</p>}
        {logs.map((log, index) => {
          const host = isHostEvent(log), speaker = players.find(p => p.playerId === log.actorId);
          const target = players.find(p => p.playerId === log.targetId);
          const mine = !host && log.actorId === myPlayerId;
          const cue = presentationText(log.metadata?.presentation, i18n.language);
          const question = log.metadata?.correlationId ? related.get(log.metadata.correlationId) : undefined;
          const newRound = log.roundNumber && log.roundNumber !== logs[index - 1]?.roundNumber;
          return <div key={eventKey(log)} className="room-event" data-testid="room-event" data-event-id={eventKey(log)}>
            {newRound ? <div className="room-round-divider">{copy("round", { n: log.roundNumber! })}</div> : null}
            {isSpeechEvent(log) ? <article className={`room-message ${mine ? "room-message-mine" : ""}`}>
              <Avatar className="h-8 w-8 shrink-0 md:h-9 md:w-9"><AvatarImage src={host ? undefined : speaker?.avatar} alt="" /><AvatarFallback>{host ? "✦" : speaker?.displayName?.[0] || "?"}</AvatarFallback></Avatar>
              <div className="room-message-body">
                <div className="room-message-byline">
                  <span className="truncate">{host ? copy("host") : speaker?.displayName || copy("players")}</span>
                  {speaker && !host && <span className="shrink-0">{copy("seat", { n: speaker.seatNumber + 1 })}{speaker.ai ? " · AI" : ""}</span>}
                  {target && !host && <span className="inline-flex min-w-0 items-center gap-1"><ArrowRight className="h-3 w-3 shrink-0" /><span className="truncate">{copy("seat", { n: target.seatNumber + 1 })}</span></span>}
                </div>
                <div className={`room-bubble ${host ? "room-bubble-host" : ""}`}>
                  {question && eventKey(question) !== eventKey(log) && <blockquote className="room-reply"><span>{copy("reply")}</span> {eventContent(question)}</blockquote>}
                  <p className="whitespace-pre-wrap break-words">{eventContent(log)}</p>
                </div>
                {cue && <p className="room-message-cue" data-testid="ai-presentation">{cue}</p>}
              </div>
            </article> : <div className="room-system-event" data-testid="game-system-event">{log.message}</div>}
          </div>;
        })}
      </div>
    </div>
    {unread && !history && <Button size="sm" className="room-new-messages" onClick={() => {
      follow.current = true; setUnread(false); if (viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
    }}><ArrowDown className="mr-1 h-4 w-4" />{copy("newEvents")}</Button>}
  </div>;
}
