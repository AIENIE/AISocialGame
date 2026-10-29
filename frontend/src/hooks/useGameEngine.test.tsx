import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { gameplayApi } from "@/services/api";
import type { GameState, PlayerAction } from "@/types";
import { prepareGameAction, useGameEngine } from "./useGameEngine";
import { HttpApiError } from "@/services/apiError";
import { useRoomActionController } from "@/pages/games/shared/useRoomActionController";

vi.mock("@/services/api", () => ({ gameplayApi: { state: vi.fn(), action: vi.fn(), start: vi.fn() } }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ i18n: { language: "zh-CN" } }) }));

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
  it("submits a preserved Werewolf question draft with the latest phase token", async () => {
    const priority: GameState = { ...snapshot("priority"), gameId: "werewolf", phase: "DAY_INTERACTION", myPlayerId: "human",
      extra: { ruleVersion: 2, archiveId: "archive-1", phaseToken: "priority", interaction: { humanPriority: true, questionTarget: "", completedPairs: 0 },
        legalActions: [{ type: "ASK_PLAYER", label: "提问", targets: ["p2", "p3"], maxLength: 60 }] } };
    const shared: GameState = { ...priority, extra: { ...priority.extra, phaseToken: "shared", interaction: { humanPriority: false, questionTarget: "", completedPairs: 0 } } };
    client.setQueryData(["game-state", "room"], priority);
    let control!: ReturnType<typeof useRoomActionController>;
    function QuestionProbe() {
      const current = useGameEngine("werewolf", "room");
      control = useRoomActionController(current.stateQuery.data!, action => current.actionMutation.mutateAsync(action));
      return null;
    }
    vi.mocked(gameplayApi.state).mockResolvedValue(shared);
    vi.mocked(gameplayApi.action).mockResolvedValue(shared);
    act(() => root.render(<QueryClientProvider client={client}><QuestionProbe /></QueryClientProvider>));
    act(() => { control.selectTarget("p2"); control.setDraft("你依据哪条公开发言？"); });
    await act(async () => { client.setQueryData(["game-state", "room"], shared); await new Promise(resolve => setTimeout(resolve, 0)); });
    expect(control.draft).toBe("你依据哪条公开发言？");
    expect(control.target).toBe("p2");
    await act(async () => { await control.submit(control.prepared); });
    expect(gameplayApi.action).toHaveBeenCalledExactlyOnceWith("werewolf", "room", expect.objectContaining({
      type: "ASK_PLAYER", targetPlayerId: "p2", content: "你依据哪条公开发言？", expectedPhaseToken: "shared", requestId: expect.any(String),
    }));
  });

  it("counts three deliberate corrections with Axios business feedback, including reuse of the same draft object", async () => {
    vi.mocked(gameplayApi.state).mockResolvedValue(snapshot("instance:1"));
    vi.mocked(gameplayApi.action).mockImplementation(async () => {
      throw Object.assign(new Error("correct"), { isAxiosError: true, response: { status: 400, data: { code: "OWN_WORD_FORBIDDEN" } } });
    });
    act(() => root.render(<QueryClientProvider client={client}><Probe /></QueryClientProvider>));
    const draft: PlayerAction = { type: "SPEAK", content: "禁词" };
    for (let attempt = 0; attempt < 3; attempt++) {
      await act(async () => { await expect(engine.actionMutation.mutateAsync(draft)).rejects.toThrow("correct"); });
    }
    const identities = vi.mocked(gameplayApi.action).mock.calls.map(call => call[2].requestId);
    expect(new Set(identities).size).toBe(3);
    expect(draft.requestId).toBeUndefined();
  });
  it("uses the private server receipt when a business error body was lost, without replaying the rejected action",async()=>{
    vi.mocked(gameplayApi.state).mockResolvedValue(snapshot("instance:1"));
    vi.mocked(gameplayApi.action).mockRejectedValueOnce(new Error("error body lost")).mockResolvedValue(snapshot("instance:1"));
    act(()=>root.render(<QueryClientProvider client={client}><Probe/></QueryClientProvider>));
    await act(async()=>{await expect(engine.actionMutation.mutateAsync({type:"SPEAK",content:"禁词"})).rejects.toThrow("error body lost");});
    const prior=vi.mocked(gameplayApi.action).mock.calls[0][2].requestId;
    act(()=>client.setQueryData(["game-state","room"],{...snapshot("instance:1"),extra:{phaseToken:"instance:1",wordFeedback:{actionRequestId:prior}}}));
    await act(async()=>{await engine.actionMutation.mutateAsync({type:"SPEAK",content:"禁词"});});
    expect(vi.mocked(gameplayApi.action).mock.calls[1][2].requestId).not.toBe(prior);
  });
  it("creates a new correction identity after a confirmed forbidden-word rejection",async()=>{
    vi.mocked(gameplayApi.state).mockResolvedValue(snapshot("instance:1"));
    vi.mocked(gameplayApi.action).mockRejectedValueOnce(new HttpApiError(400,"OWN_WORD_FORBIDDEN","correct"))
      .mockResolvedValue(snapshot("instance:1"));
    act(()=>root.render(<QueryClientProvider client={client}><Probe/></QueryClientProvider>));
    await act(async()=>{await expect(engine.actionMutation.mutateAsync({type:"SPEAK",content:"同样的词"})).rejects.toThrow("correct");});
    await act(async()=>{await engine.actionMutation.mutateAsync({type:"SPEAK",content:"同样的词"});});
    const calls=vi.mocked(gameplayApi.action).mock.calls;
    expect(calls[0][2].requestId).not.toBe(calls[1][2].requestId);
  });
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
