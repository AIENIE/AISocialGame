import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { gameplayApi } from "@/services/api";
import type { GameLogEntry, GameState } from "@/types";
import { mergeEvents, publicSequence } from "./roomEvents";

/** Mounted per archive; older pages and recovery batches cannot cross into a new game. */
export function useRoomTimeline(state: GameState) {
  const [collected, setCollected] = useState<GameLogEntry[]>([]);
  const [cursor, setCursor] = useState<number | null | undefined>();
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [olderError, setOlderError] = useState(false);
  const newest = useRef<number>();
  const active = useRef(true);
  const olderRequest = useRef<AbortController>();
  useEffect(() => { active.current = true; return () => { active.current = false; olderRequest.current?.abort(); }; }, []);
  const query = useQuery({
    queryKey: ["room-timeline", state.gameId, state.roomId, state.extra?.archiveId, state.myPlayerId, state.extra?.viewVersion],
    enabled: state.phase !== "WAITING",
    queryFn: async ({ signal }) => {
      let page = await gameplayApi.logs(state.gameId, state.roomId, undefined, 100, signal);
      let events = page.items;
      // After a long disconnection, recover the gap between the latest page and our last event.
      const after = newest.current;
      const seen = new Set<number>();
      while (after !== undefined && page.hasMore && page.nextCursor !== null &&
        (publicSequence(events[0]) ?? 0) > after + 1 && !seen.has(page.nextCursor)) {
        seen.add(page.nextCursor);
        page = await gameplayApi.logs(state.gameId, state.roomId, page.nextCursor, 100, signal);
        events = mergeEvents(page.items, events);
      }
      return { items: events, nextCursor: page.nextCursor };
    },
  });
  useEffect(() => {
    if (!query.data) return;
    setCollected(previous => mergeEvents(previous, query.data.items));
    setCursor(previous => previous === undefined ? query.data.nextCursor : previous);
    const last = query.data.items.at(-1);
    if (last) newest.current = publicSequence(last) ?? newest.current;
  }, [query.data]);
  // Live snapshots remain useful while historical requests are loading or fail.
  useEffect(() => {
    setCollected(previous => mergeEvents(previous, state.logs));
  }, [state.logs]);
  const logs = mergeEvents(collected, state.logs);
  const loadOlder = async () => {
    if (cursor == null || loadingOlder) return;
    setLoadingOlder(true); setOlderError(false);
    olderRequest.current = new AbortController();
    try {
      const page = await gameplayApi.logs(state.gameId, state.roomId, cursor, 100, olderRequest.current.signal);
      if (active.current) { setCollected(previous => mergeEvents(page.items, previous)); setCursor(page.nextCursor); }
    } catch { if (active.current) setOlderError(true); }
    finally { if (active.current) setLoadingOlder(false); }
  };
  return { logs, hasMore: cursor != null, loadOlder, loading: loadingOlder || query.isFetching,
    error: olderError || query.isError, retry: () => olderError ? loadOlder() : query.refetch() };
}
