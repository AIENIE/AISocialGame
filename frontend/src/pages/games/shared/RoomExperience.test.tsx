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
function render(state: GameState) {
  const idle = { isPending: false, isError: false, error: null, refetch: vi.fn(), mutate: vi.fn(), mutateAsync: vi.fn().mockResolvedValue(undefined) };
  vi.mocked(useRoomRuntime).mockReturnValue({ room: { ...room, gameId: state.gameId }, state, roomQuery: idle, stateQuery: idle, joinMutation: idle, personaQuery: idle, addAiMutation: idle, startMutation: idle, actionMutation: idle, authLoading: false, userKey: "p0", isHost: true, personas: [], selectedAiId: "", setSelectedAiId: vi.fn(), canAddAi: true, chatMessages: [], socket: { connected: true, showReconnectAction: false, reconnect: vi.fn(), sendChat: vi.fn() }, startGame: vi.fn(), handleActionError: vi.fn() } as unknown as ReturnType<typeof useRoomRuntime>);
  act(() => root.render(<MemoryRouter><RoomExperience gameId={state.gameId} /></MemoryRouter>));
}
beforeEach(() => { (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true; container = document.createElement("div"); document.body.append(container); root = createRoot(container); });
afterEach(() => { act(() => root.unmount()); container.remove(); vi.clearAllMocks(); });

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
