import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { gameplayApi } from "@/services/api";
import type { GameLogEntry, GameState } from "@/types";
import { useRoomTimeline } from "./useRoomTimeline";

vi.mock("@/services/api", () => ({ gameplayApi: { logs: vi.fn() } }));
const event = (n: number): GameLogEntry => ({ type: "SPEECH", message: `message ${n}`, time: "2026-10-07T12:00:00", metadata: { eventId: `e${n}`, publicSeq: n } });
const page = (ids: number[], cursor: number | null) => ({ items: ids.map(event), nextCursor: cursor, hasMore: cursor !== null });
const state = (ids: number[], version: string): GameState => ({ roomId: "room", gameId: "undercover", phase: "DESCRIPTION", round: 1, players: [], logs: ids.map(event), extra: { archiveId: "game-1", viewVersion: version } });
let root: Root, container: HTMLDivElement, client: QueryClient;
let result: ReturnType<typeof useRoomTimeline>;
function Harness({ value }: { value: GameState }) { result = useRoomTimeline(value); return <p>{result.logs.map(e => e.metadata?.publicSeq).join(",")}</p>; }
const render = (value: GameState) => act(() => root.render(<QueryClientProvider client={client}><Harness value={value} /></QueryClientProvider>));
const settled = async () => { await act(async () => { await vi.waitFor(() => expect(client.isFetching()).toBe(0)); await new Promise(resolve => setTimeout(resolve, 10)); }); };
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  container = document.createElement("div"); document.body.append(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); client.clear(); container.remove(); vi.resetAllMocks(); });

it("fills every missing page after reconnect and deduplicates the live overlap", async () => {
  vi.mocked(gameplayApi.logs).mockResolvedValueOnce(page([5, 6], 5));
  render(state([5, 6], "first")); await settled();
  vi.mocked(gameplayApi.logs).mockResolvedValueOnce(page([11, 12], 11)).mockResolvedValueOnce(page([9, 10], 9)).mockResolvedValueOnce(page([7, 8], 7));
  render(state([11, 12], "reconnected")); await settled();
  expect(container.textContent).toBe("5,6,7,8,9,10,11,12");
  expect(gameplayApi.logs).toHaveBeenNthCalledWith(3, "undercover", "room", 11, 100, expect.any(AbortSignal));
  expect(gameplayApi.logs).toHaveBeenNthCalledWith(4, "undercover", "room", 9, 100, expect.any(AbortSignal));
  vi.mocked(gameplayApi.logs).mockResolvedValueOnce(page([3, 4, 5], null));
  await act(async () => result.loadOlder());
  expect(container.textContent).toBe("3,4,5,6,7,8,9,10,11,12");
  expect(result.hasMore).toBe(false);
});

it("keeps a failed older-page cursor for retry without losing live messages", async () => {
  vi.mocked(gameplayApi.logs).mockResolvedValueOnce(page([3, 4], 3));
  render(state([3, 4], "first")); await settled();
  vi.mocked(gameplayApi.logs).mockRejectedValueOnce(new Error("offline"));
  await act(async () => result.loadOlder());
  expect(result.error).toBe(true); expect(container.textContent).toBe("3,4");
  vi.mocked(gameplayApi.logs).mockResolvedValueOnce(page([1, 2], null));
  await act(async () => result.retry());
  expect(container.textContent).toBe("1,2,3,4"); expect(result.error).toBe(false);
});

it("retains earlier snapshots when the history API is unavailable", async () => {
  vi.mocked(gameplayApi.logs).mockRejectedValue(new Error("offline"));
  render(state([1, 2], "first")); await settled();
  render(state([3, 4], "second")); await settled();
  expect(container.textContent).toBe("1,2,3,4"); expect(result.error).toBe(true);
});
