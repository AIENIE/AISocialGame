import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import "@/i18n/config";
import { RoomEntryGate } from "./RoomEntry";
import { roomApi } from "@/services/api";
import { HttpApiError } from "@/services/apiError";

vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ displayName: "测试用户" }) }));
vi.mock("@/services/api", async original => ({ ...await original<typeof import("@/services/api")>(), roomApi: { entry: vi.fn(), join: vi.fn(), detail: vi.fn() } }));
const entry = { id: "private-room", roomCode: "123456", gameId: "undercover", name: "密码房间", status: "WAITING" as const, isPrivate: true, passwordRequired: true, joined: false, seatCount: 1, maxPlayers: 4, expiresAt: null };
let root: Root, container: HTMLDivElement, client: QueryClient;
const mounted = vi.fn(), unmounted = vi.fn();
function Business() { useEffect(() => { mounted(); return unmounted; }, []); return <div>受限房间内容</div>; }
async function render() {
  await act(async () => { root.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/room/undercover/private-room"]}><Routes>
    <Route path="/room/:gameId/:roomId" element={<RoomEntryGate><Business /></RoomEntryGate>} />
  </Routes></MemoryRouter></QueryClientProvider>); await new Promise(resolve => setTimeout(resolve, 20)); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
beforeEach(() => {
  vi.clearAllMocks();
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
  vi.mocked(roomApi.entry).mockResolvedValue(entry);
});
afterEach(() => { act(() => root.unmount()); client.clear(); container.remove(); });

it("does not mount private business content before admission and supports cancelling password input", async () => {
  await render(); expect(mounted).not.toHaveBeenCalled(); expect(roomApi.detail).not.toHaveBeenCalled();
  await act(async () => { Array.from(container.querySelectorAll("button")).find(button => button.textContent === "立即加入")?.click(); });
  expect(document.querySelector('[role="dialog"]')).not.toBeNull();
  await act(async () => { Array.from(document.querySelectorAll("button")).find(button => button.textContent === "取消")?.click(); });
  expect(document.querySelector('[role="dialog"]')).toBeNull(); expect(roomApi.join).not.toHaveBeenCalled(); expect(mounted).not.toHaveBeenCalled();
});

it("mounts joined users and unmounts immediately on the private expiry event", async () => {
  vi.mocked(roomApi.entry).mockResolvedValue({ ...entry, joined: true });
  await render(); expect(mounted).toHaveBeenCalledTimes(1);
  act(() => window.dispatchEvent(new CustomEvent("room-expired", { detail: entry.id })));
  expect(unmounted).toHaveBeenCalledTimes(1); expect(container.textContent).toContain("房间已失效");
});

it("shows a durable expired state instead of mounting content or retrying automatically", async () => {
  vi.mocked(roomApi.entry).mockRejectedValue(new HttpApiError(410, "ROOM_EXPIRED", "房间已失效"));
  await render(); expect(container.textContent).toContain("房间已失效"); expect(mounted).not.toHaveBeenCalled(); expect(roomApi.entry).toHaveBeenCalledTimes(1);
});

it("rechecks the server at the deadline and tears down the mounted room", async () => {
  vi.mocked(roomApi.entry).mockResolvedValueOnce({ ...entry, joined: true, expiresAt: new Date(Date.now() + 200).toISOString() })
    .mockRejectedValue(new HttpApiError(410, "ROOM_EXPIRED", "房间已失效"));
  await render(); expect(mounted).toHaveBeenCalledTimes(1);
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 250)); });
  expect(unmounted).toHaveBeenCalledTimes(1); expect(container.textContent).toContain("房间已失效");
  expect(roomApi.entry).toHaveBeenCalledTimes(2);
});


it("aborts pending room requests and removes only this room's cached data on expiry", async () => {
  let requestSignal: AbortSignal | undefined;
  function PendingRoom() {
    useQuery({ queryKey: ["game-state", entry.id], queryFn: ({ signal }) => {
      requestSignal = signal;
      return new Promise(() => {});
    } });
    return <div>加载房间</div>;
  }
  vi.mocked(roomApi.entry).mockResolvedValue({ ...entry, joined: true });
  client.setQueryData(["room", "another-room"], { name: "其他房间" });
  await act(async () => { root.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/room/undercover/private-room"]}><Routes>
    <Route path="/room/:gameId/:roomId" element={<RoomEntryGate><PendingRoom /></RoomEntryGate>} />
  </Routes></MemoryRouter></QueryClientProvider>); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 30)); });
  expect(requestSignal?.aborted).toBe(false);
  act(() => window.dispatchEvent(new CustomEvent("room-expired", { detail: entry.id })));
  expect(requestSignal?.aborted).toBe(true);
  expect(client.getQueryCache().find({ queryKey: ["game-state", entry.id] })).toBeUndefined();
  expect(client.getQueryData(["room", "another-room"])).toEqual({ name: "其他房间" });
});
