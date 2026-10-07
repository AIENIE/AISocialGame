import { beforeEach, expect, it, vi } from "vitest";
import { gameApi, roomApi, gameplayApi } from "./api";
import type { Game, Room, PagedResponse } from "@/types";
import { quickMatchApi } from "./v2Social";
vi.mock("./api", () => ({ gameApi: { detail: vi.fn() }, roomApi: { list: vi.fn(), create: vi.fn(), join: vi.fn(), detail: vi.fn(), addAi: vi.fn() }, gameplayApi: { start: vi.fn() }, personaApi: { list: vi.fn() } }));
beforeEach(() => { vi.clearAllMocks(); vi.mocked(gameApi.detail).mockResolvedValue({ id: "undercover", name: "谁是卧底", minPlayers: 4 } as Game); });
it("joins an actual waiting room and reports auto-start only after the server accepts it", async () => {
  const room = { id: "real-room", status: "WAITING", maxPlayers: 4, seatCount: 1, seats: [{ playerId: "host" }] } as Room;
  vi.mocked(roomApi.list).mockResolvedValue({ items: [room] } as PagedResponse<Room>);
  vi.mocked(roomApi.join).mockResolvedValue({ ...room, selfPlayerId: "self" });
  vi.mocked(roomApi.detail).mockResolvedValue({ ...room, seats: Array.from({ length: 4 }, (_, i) => ({ seatNumber: i, playerId: String(i), displayName: String(i), ai: false, ready: true, host: i === 0 })) });
  vi.mocked(gameplayApi.start).mockRejectedValue(new Error("not host"));
  expect(await quickMatchApi.start("undercover", "真实用户")).toEqual({ roomId: "real-room", playerId: "self", autoStarted: false });
  expect(roomApi.create).not.toHaveBeenCalled(); expect(roomApi.join).toHaveBeenCalledWith("undercover", "real-room", "真实用户");
});
it("propagates server failure and never creates local substitutes", async () => {
  vi.mocked(roomApi.list).mockRejectedValue(new Error("offline"));
  const storage = vi.spyOn(Storage.prototype, "setItem");
  await expect(quickMatchApi.start("undercover", "真实用户")).rejects.toThrow("offline");
  expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
});
