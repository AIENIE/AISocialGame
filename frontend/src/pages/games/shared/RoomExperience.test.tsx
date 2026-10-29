import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GameState, Room } from "@/types";
import { RoomExperience } from "./RoomExperience";
import { useRoomRuntime } from "./useRoomRuntime";

vi.mock("./useRoomRuntime", () => ({ useRoomRuntime: vi.fn() }));
vi.mock("./useRoomTimeline", () => ({ useRoomTimeline: (state: GameState) => ({ logs: state.logs, hasMore: false, loading: false, error: false, loadOlder: vi.fn(), retry: vi.fn() }) }));
vi.mock("react-i18next", async importOriginal => ({ ...await importOriginal<typeof import("react-i18next")>(), useTranslation: () => ({ t: (key: string) => key, i18n: { language: "zh-CN", changeLanguage: vi.fn() } }) }));
vi.mock("@/i18n/gameTexts", () => ({ gameName: (id: string) => id }));
let container: HTMLDivElement, root: Root;
const seats = Array.from({ length: 4 }, (_, i) => ({ playerId: `p${i}`, displayName: `玩家${i + 1}`, avatar: "", seatNumber: i, ready: true, host: i === 0, ai: i > 0 }));
const room: Room = { id: "room", gameId: "undercover", name: "测试房间", status: "WAITING", seats, maxPlayers: 6, hostUserId: "p0", isPrivate: false, config: {} };
const gameState = (gameId: string, phase: string, archive = "game-1"): GameState => ({ roomId: "room", gameId, phase, round: 1, myPlayerId: "p0", myRole: gameId === "werewolf" ? "SEER" : "CIVILIAN", myWord: "茶杯", players: phase === "WAITING" ? [] : seats.map(s => ({ ...s, alive: true })), logs: [{ type: "SPEECH", actorId: "p1", message: "玩家2：线索", time: "2026-10-07T12:00:00", metadata: { eventId: "message", content: "可以直接阅读的发言" } }], extra: { archiveId: archive, phaseToken: phase, ruleVersion: 2, legalActions: phase === "WAITING" || phase === "SETTLEMENT" ? [] : [{ type: "SPEAK", label: "发言", targets: [], maxLength: 100 }], seerChecks: [{ targetPlayerId: "p1", round: 1, result: "WOLF" }] } });
function render(state: GameState, overrides: Partial<Room> = {}) {
  const idle = { isPending: false, isError: false, error: null, refetch: vi.fn(), mutate: vi.fn(), mutateAsync: vi.fn().mockResolvedValue(undefined) };
  vi.mocked(useRoomRuntime).mockReturnValue({ room: { ...room, ...overrides, gameId: state.gameId }, state, roomQuery: idle, stateQuery: idle, joinMutation: idle, personaQuery: idle, addAiMutation: idle, startMutation: idle, actionMutation: idle, authLoading: false, userKey: "p0", isHost: true, personas: [], selectedAiId: "", setSelectedAiId: vi.fn(), canAddAi: true, chatMessages: [], socket: { connected: true, showReconnectAction: false, reconnect: vi.fn(), sendChat: vi.fn() }, startGame: vi.fn(), handleActionError: vi.fn() } as unknown as ReturnType<typeof useRoomRuntime>);
  act(() => root.render(<MemoryRouter><RoomExperience gameId={state.gameId} /></MemoryRouter>));
}
beforeEach(() => { (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true; container = document.createElement("div"); document.body.append(container); root = createRoot(container); });
afterEach(() => { act(() => root.unmount()); container.remove(); vi.useRealTimers(); vi.clearAllMocks(); });

it.each(["undercover", "werewolf", "turtle_soup"])("uses room seats while waiting and keeps settlement visible after the room resets: %s", game => {
  render(gameState(game, "WAITING"));
  expect(container.querySelector('[data-stage="waiting"]')).not.toBeNull();
  expect(container.querySelectorAll('[data-player-id]')).toHaveLength(4);
  expect(container.querySelector("input")).not.toBeNull();
  render({ ...gameState(game, "SETTLEMENT"), winner: "SOLVED" });
  expect(container.querySelector('[data-stage="settlement"]')).not.toBeNull();
  expect(container.querySelector('[data-testid="game-settlement-panel"]')).not.toBeNull();
  expect(container.querySelector('[data-testid="game-start-btn"]')?.textContent).toContain("再来一局");
  expect(container.querySelector("input")).not.toBeNull();
});

it("shows speech immediately, keeps setup absent, and opens room chat read-only", () => {
  render(gameState("werewolf", "DAY_DISCUSS"));
  expect(container.textContent).toContain("可以直接阅读的发言");
  expect(container.querySelector("input")).toBeNull();
  expect(container.querySelector('[data-testid="game-add-ai-btn"]')).toBeNull();
  expect(container.querySelector('[data-testid="werewolf-rules-preview"]')).toBeNull();
  expect(container.querySelector('[data-testid="werewolf-private-checks"]')).toBeNull();
  act(() => container.querySelector<HTMLButtonElement>('[data-testid="room-chat-toggle"]')!.click());
  expect(document.querySelector('[role="dialog"]')?.textContent).toContain("局中仅供查看");
  expect(document.querySelector('[role="dialog"] input')).toBeNull();
});

it("marks only AI using the game's public trial snapshot and clears badges for another game or waiting roster", () => {
  const value = gameState("werewolf", "DAY_DISCUSS");
  const strategyTrials = {
    p0: { mode: "TRIAL", strategyVersionId: "human-must-not-show", sequenceNumber: 2 },
    p1: { mode: "TRIAL", strategyVersionId: "strategy-2", sequenceNumber: 2 },
  };
  render({ ...value, extra: { ...value.extra, strategyTrials } });
  expect(container.querySelectorAll('[data-testid="strategy-trial-badge"]')).toHaveLength(1);
  const badge = container.querySelector('[data-player-id="p1"] [data-testid="strategy-trial-badge"]')!;
  expect(badge.textContent).toBe("试用，效果待验证");expect(badge.getAttribute("title")).toContain("v2");
  expect(container.textContent).not.toContain("strategy-2");
  render({ ...gameState("werewolf", "WAITING"), extra: { strategyTrials } });
  expect(container.querySelector('[data-testid="strategy-trial-badge"]')).toBeNull();
  render({ ...gameState("undercover", "DESCRIPTION"), extra: { strategyTrials } });
  expect(container.querySelector('[data-testid="strategy-trial-badge"]')).toBeNull();
  render({ ...value, extra: { ...value.extra, strategyTrials: {} } });
  expect(container.querySelector('[data-testid="strategy-trial-badge"]')).toBeNull();
});

it("resets a draft when a new archive starts", () => {
  render(gameState("undercover", "DESCRIPTION"));
  act(() => {
    const input = container.querySelector("textarea")!;
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!.call(input, "上一局的草稿");
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  expect(container.querySelector("textarea")!.value).toBe("上一局的草稿");
  render(gameState("undercover", "DESCRIPTION", "game-2"));
  expect(container.querySelector("textarea")!.value).toBe("");
});
it.each(["WAITING", "SETTLEMENT"])("keeps laboratory scheduling and roster controls outside the ordinary room setup: %s", phase => {
  render(gameState("undercover", phase), { source: "LAB", labExperimentId: "experiment" });
  expect(container.querySelector('[data-testid="game-start-btn"]')).toBeNull();
  expect(container.textContent).not.toContain("下一局准备");
  expect(container.textContent).not.toContain("邀请与补位");
  expect(container.querySelector('a[href="/lab/experiment"]')).not.toBeNull();
  expect(container.textContent).toContain("lab.managedGame");
});

it("keeps room rules accessible during a Werewolf game", async () => {
  render(gameState("werewolf", "DAY_DISCUSS"));
  const menu = container.querySelector<HTMLButtonElement>('button[aria-label="房间菜单"]')!;
  await act(async () => menu.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true })));
  const rules = document.querySelector<HTMLElement>('[data-testid="room-rules-menu"]');
  expect(rules).not.toBeNull();
  await act(async () => rules!.click());
  expect(document.querySelector('[role="dialog"] [data-testid="werewolf-rules-help"]')?.textContent).toContain("屠边");
});

it.each([
  ["DEATH_ACTION", "HUNTER_SHOOT", "HUNTER"],
  ["LAST_WORDS", "SPEAK", "VILLAGER"],
])("keeps an eliminated player's authorized %s action available", (phase, type, role) => {
  const value = gameState("werewolf", phase);
  render({ ...value, myRole: role, players: value.players.map(p => p.playerId === "p0" ? { ...p, alive: false } : p), extra: {
    ...value.extra, actionEndsAt: new Date(Date.now() + 60000).toISOString(),
    legalActions: [{ type, label: "行动", targets: type === "HUNTER_SHOOT" ? ["p1"] : [], maxLength: 120 }, { type: "SKIP", label: "跳过", targets: [], maxLength: 0 }],
  } });
  expect(container.querySelector('[data-testid="v2-action-submit"]')).not.toBeNull();
  expect(container.querySelector('.room-task')?.textContent).toBe("轮到你了");
  expect(container.textContent).toMatch(/\d+s/);
});

it("shows an expired Werewolf action while preserving the submitted-ballot AI wait", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-25T12:00:00Z"));
  const value = gameState("werewolf", "DAY_VOTE");
  const deadline = new Date(Date.now() + 1500).toISOString();
  render({ ...value, extra: { ...value.extra, actionEndsAt: deadline,
    legalActions: [{ type: "VOTE", label: "投票", targets: ["p1", "p2"], maxLength: 120 }], waitingForAi: true, pendingAiCount: 2 } });
  expect(container.querySelector('.room-task')?.textContent).toBe("轮到你了");
  act(() => vi.advanceTimersByTime(1499));
  expect(container.querySelector('.room-status-strip')?.textContent).toContain("1s");
  expect(container.querySelector<HTMLButtonElement>('[data-testid="room-choose-target"]')!.disabled).toBe(false);
  act(() => vi.advanceTimersByTime(1));
  expect(container.querySelector('.room-task')?.textContent).toBe("本次行动时间已到，等待局面更新");
  expect(container.querySelector('.room-status-strip')?.textContent).toContain("时间已到");
  expect(container.querySelector<HTMLButtonElement>('[data-testid="room-choose-target"]')!.disabled).toBe(true);
  expect(container.querySelector('[data-player-id="p1"]')?.getAttribute("aria-disabled")).toBe("true");
  render({ ...value, extra: { ...value.extra, actionEndsAt: deadline, legalActions: [], myVote: "p1", waitingForAi: true, pendingAiCount: 2 } });
  expect(container.querySelector('.room-task')?.textContent).toBe("已提交，等待下一步");
  expect(container.textContent).toContain("还有 2 名 AI 正在完成投票");
  expect(container.textContent).not.toContain("时间已到");
  expect(container.querySelector('[data-testid="v2-action-submit"]')).toBeNull();
});
