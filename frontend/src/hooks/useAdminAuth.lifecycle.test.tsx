import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AdminAuthProvider, useAdminAuth } from "./useAdminAuth";
import type { AdminLoginResult } from "@/types";
const state = vi.hoisted(() => ({ me: vi.fn(), login: vi.fn(), logout: vi.fn() }));
vi.mock("@/services/api", () => ({ adminApi: { me: () => state.me(), login: () => state.login(), logout: () => state.logout() } }));
let root: Root, container: HTMLDivElement;
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
function Probe() {
  const auth = useAdminAuth();
  return <div><span>{auth.admin?.username ?? "anonymous"}</span><button onClick={() => void auth.login("admin","fixture")}>login</button><button onClick={() => void auth.logout()}>logout</button></div>;
}
beforeEach(() => {
  Reflect.set(globalThis,"IS_REACT_ACT_ENVIRONMENT",true);
  container=document.createElement("div");document.body.appendChild(container);root=createRoot(container);
  state.me.mockReset(); state.login.mockReset(); state.logout.mockReset();
  state.me.mockResolvedValue(null); state.logout.mockResolvedValue(undefined);
});
afterEach(() => { act(() => root.unmount()); container.remove(); });
async function click(text: string) { await act(async () => Array.from(container.querySelectorAll("button")).find(button => button.textContent === text)?.click()); }
it("logout prevents an earlier login response from restoring administrator state", async () => {
  await act(async () => root.render(<AdminAuthProvider><Probe /></AdminAuthProvider>));
  const old=deferred<AdminLoginResult>();state.login.mockReturnValueOnce(old.promise);
  await click("login");await click("logout");
  state.me.mockResolvedValue({ username: "OLD_ADMIN", sessionScope: "FULL" });
  await act(async () => old.resolve({state:"AUTHENTICATED",sessionScope:"FULL"}));
  expect(container.textContent).not.toContain("OLD_ADMIN");expect(state.me).toHaveBeenCalledTimes(1);
});
it("pagehide invalidates old authenticated state while a new request after pageshow succeeds", async () => {
  await act(async () => root.render(<AdminAuthProvider><Probe /></AdminAuthProvider>));
  const old=deferred<AdminLoginResult>();state.login.mockReturnValueOnce(old.promise);
  await click("login");await act(async () => { window.dispatchEvent(new Event("pagehide"));window.dispatchEvent(new Event("pageshow")); });
  state.me.mockResolvedValue({ username: "FRESH_ADMIN", sessionScope: "FULL" });
  await act(async () => old.resolve({state:"AUTHENTICATED",sessionScope:"FULL"}));
  expect(container.textContent).not.toContain("FRESH_ADMIN");expect(state.me).toHaveBeenCalledTimes(1);
  state.login.mockResolvedValue({state:"AUTHENTICATED",sessionScope:"FULL"});await click("login");
  expect(container.textContent).toContain("FRESH_ADMIN");
});
