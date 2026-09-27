import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { gameplayApi } from "@/services/api";
import type { GameState, PlayerAction } from "@/types";
import { prepareGameAction, useGameEngine } from "./useGameEngine";

vi.mock("@/services/api", () => ({ gameplayApi: { state: vi.fn(), action: vi.fn(), start: vi.fn() } }));

const snapshot = (token: string): GameState => ({ roomId: "room", gameId: "turtle_soup", phase: "QUESTIONING", round: 1, players: [], logs: [], extra: { ruleVersion: 2, phaseToken: token } });
let container: HTMLDivElement;
let root: Root;
let client: QueryClient;
let engine: ReturnType<typeof useGameEngine>;

function Probe() { engine = useGameEngine("turtle_soup", "room"); return null; }

beforeEach(() => {
  vi.clearAllMocks();
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(["game-state", "room"], snapshot("instance:1"));
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); client.clear(); container.remove(); });

describe("game action submission", () => {
  it("adds submission identity and the displayed phase without modifying the draft", () => {
    const draft: PlayerAction = { type: "DISCUSS", content: "想法" };
    const first = prepareGameAction(draft, snapshot("instance:1"));
    const next = prepareGameAction(draft, snapshot("instance:1"));
    expect(first.expectedPhaseToken).toBe("instance:1");
    expect(first.requestId).toBeTruthy(); expect(first.requestId).not.toBe(next.requestId);
    expect(draft.requestId).toBeUndefined();
    expect(prepareGameAction({ ...first }, snapshot("instance:2"))).toEqual(first);
  });

  it("updates the query cache with the server response before a background refresh completes", async () => {
    let finishRefresh!: (state: GameState) => void;
    vi.mocked(gameplayApi.state).mockImplementation(() => new Promise<GameState>((resolve) => { finishRefresh = resolve; }));
    vi.mocked(gameplayApi.action).mockResolvedValue(snapshot("instance:2"));
    act(() => root.render(<QueryClientProvider client={client}><Probe /></QueryClientProvider>));
    let completion!: Promise<GameState>;
    await act(async () => {
      completion = engine.actionMutation.mutateAsync({ type: "DISCUSS", content: "我想确认一下。" });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(client.getQueryData<GameState>(["game-state", "room"])?.extra?.phaseToken).toBe("instance:2");
    expect(gameplayApi.action).toHaveBeenCalledWith("turtle_soup", "room", expect.objectContaining({ requestId: expect.any(String), expectedPhaseToken: "instance:1" }));
    await act(async () => { finishRefresh(snapshot("instance:2")); await completion; });
  });

  it("keeps the same key for retrying a logical submission and reads new state for a new submission", async () => {
    vi.mocked(gameplayApi.state).mockResolvedValue(snapshot("instance:2"));
    vi.mocked(gameplayApi.action).mockResolvedValue(snapshot("instance:2"));
    act(() => root.render(<QueryClientProvider client={client}><Probe /></QueryClientProvider>));
    const draft: PlayerAction = { type: "DISCUSS", content: "同一次提交" };
    await act(async () => { await engine.actionMutation.mutateAsync(draft); });
    await act(async () => { await engine.actionMutation.mutateAsync(draft); });
    await act(async () => { await engine.actionMutation.mutateAsync({ type: "DISCUSS", content: "新的想法" }); });
    const calls = vi.mocked(gameplayApi.action).mock.calls;
    expect(calls[0][2].requestId).toBe(calls[1][2].requestId);
    expect(calls[1][2].expectedPhaseToken).toBe("instance:1");
    expect(calls[2][2].requestId).not.toBe(calls[0][2].requestId);
    expect(calls[2][2].expectedPhaseToken).toBe("instance:2");
  });

  it("reuses the unconfirmed request after a lost reply even when the form creates a new payload object", async () => {
    vi.mocked(gameplayApi.state).mockResolvedValue(snapshot("instance:1"));
    vi.mocked(gameplayApi.action).mockRejectedValueOnce(new Error("reply lost")).mockResolvedValue(snapshot("instance:1"));
    act(() => root.render(<QueryClientProvider client={client}><Probe /></QueryClientProvider>));
    await act(async () => { await expect(engine.actionMutation.mutateAsync({ type: "DISCUSS", content: "这次想法" })).rejects.toThrow("reply lost"); });
    await act(async () => { await engine.actionMutation.mutateAsync({ type: "DISCUSS", content: "这次想法" }); });
    const calls = vi.mocked(gameplayApi.action).mock.calls;
    expect(calls[0][2].requestId).toBe(calls[1][2].requestId);
  });
});
