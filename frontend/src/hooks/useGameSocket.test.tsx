import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useGameSocket } from "./useGameSocket";

class FakeSocket {
  static OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = 1;
  sent: string[] = [];
  onopen?: () => void;
  onmessage?: (event: { data: string }) => void;
  onclose?: () => void;
  onerror?: () => void;
  constructor(readonly url: string) { FakeSocket.instances.push(this); }
  send(frame: string) { this.sent.push(frame); }
  close() { if (this.readyState === 3) return; this.readyState = 3; this.onclose?.(); }
  receive(data: string) { this.onmessage?.({ data }); }
  connect() { this.onopen?.(); this.receive("CONNECTED\nheart-beat:10000,10000\n\n\0"); }
  sync() {
    const frame = this.sent.find(sent => sent.startsWith("SEND\n") && sent.includes("/sync"));
    if (!frame) throw new Error("sync request missing");
    const nonce = (JSON.parse(frame.split("\n\n")[1].replace("\0", "")) as { nonce: string }).nonce;
    const roomId = frame.match(/destination:\/app\/room\/([^/]+)\/sync/)?.[1];
    this.receive(`MESSAGE\ndestination:/user/queue/private\n\n${JSON.stringify({ type: "SYNC_READY", payload: { roomId, nonce } })}\0`);
  }
}
let root: Root;
let container: HTMLDivElement;
let options: Parameters<typeof useGameSocket>[0];
let socket: ReturnType<typeof useGameSocket>;
function Probe() { socket = useGameSocket(options); return null; }
beforeEach(() => {
  vi.useFakeTimers(); vi.stubGlobal("WebSocket", FakeSocket); FakeSocket.instances = [];
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div"); root = createRoot(container);
  options = { roomId: "room", playerId: "user", token: "token", onConnected: vi.fn(), onStateChange: vi.fn() };
});
afterEach(() => { act(() => root.unmount()); expect(vi.getTimerCount()).toBe(0); vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers(); container.remove(); });

it("subscribes and requests snapshots on every connection, ignoring old callbacks", () => {
  act(() => root.render(<Probe />));
  const first = FakeSocket.instances[0];
  act(() => first.connect());
  expect(first.sent.filter(frame => frame.startsWith("SUBSCRIBE"))).toHaveLength(4);
  expect(options.onConnected).not.toHaveBeenCalled();
  act(() => first.sync());
  expect(options.onConnected).toHaveBeenCalledTimes(1);
  act(() => first.close());
  act(() => vi.advanceTimersByTime(1300));
  const second = FakeSocket.instances[1];
  act(() => second.connect());
  act(() => second.sync());
  expect(options.onConnected).toHaveBeenCalledTimes(2);
  act(() => { first.onclose?.(); first.receive("CONNECTED\n\n\0"); });
  expect(socket.connected).toBe(true);
  expect(options.onConnected).toHaveBeenCalledTimes(2);
});

it("uses current callbacks without reconnecting and buffers split STOMP frames", () => {
  act(() => root.render(<Probe />)); const first = FakeSocket.instances[0]; act(() => { first.connect(); first.sync(); });
  const updated = vi.fn(); options = { ...options, onStateChange: updated };
  act(() => root.render(<Probe />));
  act(() => first.receive('MESSAGE\ndestination:/topic/room/room/state\n\n{"type":"STATE_'));
  expect(updated).not.toHaveBeenCalled();
  act(() => first.receive('SYNC"}\0'));
  expect(updated).toHaveBeenCalledWith({ type: "STATE_SYNC" });
  expect(FakeSocket.instances).toHaveLength(1);
});

it("sends real heartbeats, accepts inbound heartbeat activity and reconnects on silence", () => {
  act(() => root.render(<Probe />)); const first = FakeSocket.instances[0]; act(() => { first.connect(); first.sync(); });
  act(() => vi.advanceTimersByTime(10_000)); expect(first.sent).toContain("\n");
  act(() => { first.receive("\n"); vi.advanceTimersByTime(20_000); }); expect(first.readyState).toBe(1);
  act(() => vi.advanceTimersByTime(7_500)); expect(first.readyState).toBe(3); expect(FakeSocket.instances).toHaveLength(2);
});

it("token changes and logout invalidate callbacks from the previous connection", () => {
  act(() => root.render(<Probe />)); const first = FakeSocket.instances[0]; act(() => { first.connect(); first.sync(); });
  options = { ...options, token: "new-token", roomId: "other-room" }; act(() => root.render(<Probe />));
  const second = FakeSocket.instances[1]; act(() => { second.connect(); second.sync(); });
  act(() => { first.receive('MESSAGE\ndestination:/topic/room/room/state\n\n{}\0'); first.onclose?.(); });
  expect(options.onStateChange).not.toHaveBeenCalled(); expect(socket.connected).toBe(true);
  options = { ...options, token: null }; act(() => root.render(<Probe />));
  act(() => { second.onclose?.(); vi.advanceTimersByTime(60_000); });
  expect(FakeSocket.instances).toHaveLength(2); expect(socket.connected).toBe(false);
});

it("immediately disconnects while offline and requires fresh sync after reconnecting", () => {
  act(() => root.render(<Probe />));
  const first = FakeSocket.instances[0];
  act(() => { first.connect(); first.sync(); });
  expect(socket.connected).toBe(true);
  act(() => window.dispatchEvent(new Event("offline")));
  expect(socket.connected).toBe(false);
  expect(first.readyState).toBe(3);
  expect(socket.sendChat("TEXT", "offline message")).toBe(false);
  act(() => { first.sync(); vi.advanceTimersByTime(60_000); socket.reconnect(); });
  expect(FakeSocket.instances).toHaveLength(1);
  expect(socket.connected).toBe(false);
  act(() => window.dispatchEvent(new Event("online")));
  const second = FakeSocket.instances[1];
  act(() => second.connect());
  expect(socket.connected).toBe(false);
  act(() => second.sync());
  expect(socket.connected).toBe(true);
  expect(options.onConnected).toHaveBeenCalledTimes(2);
});

it("waits for online before opening a socket when the room mounts offline", () => {
  vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
  act(() => root.render(<Probe />));
  act(() => vi.advanceTimersByTime(60_000));
  expect(FakeSocket.instances).toHaveLength(0);
  expect(socket.connected).toBe(false);
  act(() => window.dispatchEvent(new Event("online")));
  expect(FakeSocket.instances).toHaveLength(1);
});
