import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { personaApi, roomApi } from "@/services/api";
import type { Room } from "@/types";
import { useRoomRuntime } from "./useRoomRuntime";
import { PersonaPicker } from "./PersonaPicker";

vi.mock("react-router-dom", () => ({ useParams: () => ({ roomId: "room" }) }));
vi.mock("react-i18next", async importOriginal => ({ ...await importOriginal<typeof import("react-i18next")>(), useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ user: { id: "host" }, displayName: "真人", loading: false }) }));
vi.mock("@/hooks/useGameSocket", () => ({ useGameSocket: () => ({}) }));
vi.mock("@/hooks/useGameEngine", () => ({ useGameEngine: () => ({ stateQuery: { refetch: vi.fn() }, startMutation: {}, actionMutation: {} }) }));
vi.mock("@/services/api", () => ({ personaApi: { list: vi.fn() }, roomApi: { detail: vi.fn(), addAi: vi.fn(), join: vi.fn() }, getApiErrorMessage: () => "failed" }));
let container: HTMLDivElement, root: Root, client: QueryClient;
let runtime: ReturnType<typeof useRoomRuntime>;
const room = { id: "room", gameId: "undercover", status: "WAITING", maxPlayers: 2, hostUserId: "host",
  seats: [{ playerId: "host", displayName: "真人", seatNumber: 0, host: true, ai: false, ready: true, avatar: "" }] } as Room;
function Harness({ game }: { game: string }) {
  runtime = useRoomRuntime({ defaultGameId: game });
  return <PersonaPicker personas={runtime.personas} selectedAiId={runtime.selectedAiId} onSelectedAiIdChange={runtime.setSelectedAiId}
    canAddAi={runtime.canAddAi} isAdding={runtime.addAiMutation.isPending} onAddAi={() => runtime.addAiMutation.mutate(runtime.selectedAiId)} />;
}
beforeEach(() => {
  vi.clearAllMocks();
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(["room", "room"], room);
  client.setQueryData(["personas"], [1, 2].map(i => ({ id: `ai${i}`, name: `角色${i}`, trait: "风格", avatar: "" })));
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); client.clear(); container.remove(); });

it.each(["undercover", "werewolf", "turtle_soup"])("sends only the selected persona ID for %s and respects pending/full responses", async game => {
  let finish!: (room: Room) => void;
  vi.mocked(roomApi.addAi).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  // Leave the subsequent refresh pending: the successful response must update capacity immediately.
  vi.mocked(roomApi.detail).mockImplementation(() => new Promise(() => {}));
  act(() => root.render(<QueryClientProvider client={client}><Harness game={game} /></QueryClientProvider>));
  act(() => container.querySelector<HTMLInputElement>('input[value="ai2"]')!.click());
  const button = () => container.querySelector<HTMLButtonElement>('[data-testid="game-add-ai-btn"]')!;
  await act(async () => { button().click(); button().click(); await new Promise(resolve => setTimeout(resolve, 10)); });
  expect(roomApi.addAi).toHaveBeenCalledExactlyOnceWith(game, "room", "ai2");
  expect(button().disabled).toBe(true);
  await act(async () => {
    finish({ ...room, seats: [...room.seats, { playerId: "ai-seat", personaId: "ai2", displayName: "生成昵称", ai: true, seatNumber: 1, ready: true, avatar: "", host: false }] });
    await new Promise(resolve => setTimeout(resolve, 10));
  });
  expect(runtime.room?.seats[1].personaId).toBe("ai2");
  expect(runtime.canAddAi).toBe(false); expect(button().disabled).toBe(true);
  expect(personaApi.list).not.toHaveBeenCalled();
});
