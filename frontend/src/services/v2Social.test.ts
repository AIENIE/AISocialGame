import { beforeEach, expect, it, vi } from "vitest";
import { gameApi, roomApi, gameplayApi, personaApi } from "./api";
import type { Game, Room, PagedResponse, Persona } from "@/types";
import { quickMatchApi } from "./v2Social";

vi.mock("./api", () => ({ gameApi: { detail: vi.fn() }, roomApi: { list: vi.fn(), create: vi.fn(), join: vi.fn(), detail: vi.fn(), addAi: vi.fn() }, gameplayApi: { start: vi.fn() }, personaApi: { list: vi.fn() } }));
const seat = (index: number) => ({ seatNumber: index, playerId: index ? `ai-${index}` : "host", displayName: `玩家${index}`, ai: index > 0, ready: true, host: index === 0 });
beforeEach(() => { vi.resetAllMocks(); });

function waitingRoom(count: number, occupied = 1): Room {
  return { id: "real-room", name: "狼人房间", gameId: "werewolf", status: "WAITING", maxPlayers: count, isPrivate: false,
    config: { playerCount: count }, hostUserId: "host", selfPlayerId: "host", seats: Array.from({ length: occupied }, (_, i) => seat(i)) };
}
function configure(room: Room, existing = false) {
  vi.mocked(gameApi.detail).mockResolvedValue({ id: "werewolf", name: "狼人杀", minPlayers: 6, configSchema: [{ id: "playerCount", defaultValue: room.maxPlayers }] } as Game);
  vi.mocked(roomApi.list).mockResolvedValue({ items: existing ? [room] : [] } as PagedResponse<Room>);
  vi.mocked(roomApi.create).mockResolvedValue(room);
  vi.mocked(roomApi.join).mockResolvedValue(room);
  vi.mocked(roomApi.detail).mockResolvedValue(room);
  vi.mocked(personaApi.list).mockResolvedValue([{ id: "ai1" }, { id: "ai2" }] as Persona[]);
  let current = room;
  vi.mocked(roomApi.addAi).mockImplementation(async () => {
    current = { ...current, seats: [...current.seats, seat(current.seats.length)] };
    return current;
  });
}

it.each([6, 9, 12])("fills all %i configured Werewolf seats before starting", async count => {
  configure(waitingRoom(count));
  vi.mocked(gameplayApi.start).mockImplementation(async () => {
    expect(roomApi.addAi).toHaveBeenCalledTimes(count - 1);
    return {} as Awaited<ReturnType<typeof gameplayApi.start>>;
  });
  expect(await quickMatchApi.start("werewolf", "真实用户")).toEqual({ roomId: "real-room", playerId: "host", autoStarted: true });
  expect(roomApi.create).toHaveBeenCalledOnce();
  expect(gameplayApi.start).toHaveBeenCalledExactlyOnceWith("werewolf", "real-room");
});

it("only joins and waits when the current player is not the host", async () => {
  configure({ ...waitingRoom(9), selfPlayerId: "guest" }, true);
  expect(await quickMatchApi.start("werewolf", "真实用户")).toEqual({ roomId: "real-room", playerId: "guest", autoStarted: false });
  expect(roomApi.create).not.toHaveBeenCalled();
  expect(roomApi.addAi).not.toHaveBeenCalled();
  expect(gameplayApi.start).not.toHaveBeenCalled();
});

it.each(["join", "addAi", "start"])("keeps the created room and reports a %s failure without recreating it", async step => {
  configure(waitingRoom(12));
  const failed = step === "start" ? gameplayApi.start : step === "join" ? roomApi.join : roomApi.addAi;
  vi.mocked(failed).mockRejectedValue(new Error("请求暂时失败"));
  expect(await quickMatchApi.start("werewolf", "真实用户")).toMatchObject({ roomId: "real-room", autoStarted: false, failure: "请求暂时失败" });
  expect(roomApi.create).toHaveBeenCalledOnce();
  if (step !== "start") expect(gameplayApi.start).not.toHaveBeenCalled();
});

it("reports unavailable AI seats instead of trying to start a partial room", async () => {
  configure(waitingRoom(6));
  vi.mocked(personaApi.list).mockResolvedValue([]);
  expect(await quickMatchApi.start("werewolf", "真实用户")).toMatchObject({ roomId: "real-room", autoStarted: false, failure: expect.any(String) });
  expect(gameplayApi.start).not.toHaveBeenCalled();
});

it("propagates discovery failure without creating a substitute room", async () => {
  configure(waitingRoom(6));
  vi.mocked(roomApi.list).mockRejectedValue(new Error("offline"));
  await expect(quickMatchApi.start("werewolf", "真实用户")).rejects.toThrow("offline");
  expect(roomApi.create).not.toHaveBeenCalled();
});
