import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "./useAuth";
import { safeReturnTo, consumeReturnTo, LOCAL_RETURN_TO_KEY } from "./authStorage";
import { RequireAuth } from "@/components/auth/RequireAuth";
const api = vi.hoisted(() => ({ me: vi.fn(), logout: vi.fn(), callback: vi.fn(), expired: () => {}, token: vi.fn() }));
vi.mock("@/services/api", () => ({ authApi: { me: api.me, logout: api.logout, ssoCallback: api.callback }, setAuthToken: api.token,
  subscribeAuthExpired: (listener: () => void) => { api.expired = listener; return () => {}; } }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock("sonner", () => ({ toast: { error: vi.fn() } }));
let root: Root, element: HTMLDivElement, auth: ReturnType<typeof useAuth>;
let mounts = 0;
function Probe() { auth = useAuth(); return null; }
function Private() { mounts++; return <p>PRIVATE_DATA</p>; }
async function render() { await act(async () => { root.render(<AuthProvider><Probe /><MemoryRouter initialEntries={["/profile?tab=wallet"]}><Routes><Route element={<RequireAuth />}><Route path="/profile" element={<Private />} /></Route></Routes></MemoryRouter></AuthProvider>); }); }
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); mounts = 0; (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  element = document.createElement("div"); document.body.append(element); root = createRoot(element); });
afterEach(() => { act(() => root.unmount()); element.remove(); });
it("does not mount private content or call me without a token", async () => { await render(); expect(mounts).toBe(0); expect(api.me).not.toHaveBeenCalled(); expect(element.textContent).toContain("auth.required"); });
it("waits for session validation before mounting private content", async () => { sessionStorage.setItem("aisocialgame_token", "valid"); let complete!: (user: unknown) => void; api.me.mockImplementation(() => new Promise(resolve => { complete = resolve; })); await render(); expect(mounts).toBe(0); expect(element.textContent).toContain("auth.checking"); await act(async () => { complete({ id: "A", nickname: "Alice" }); }); expect(element.textContent).toContain("PRIVATE_DATA"); });
it("preserves the token after network failure and allows retry", async () => { sessionStorage.setItem("aisocialgame_token", "valid"); api.me.mockRejectedValue(new Error("network")); await render(); expect(auth.status).toBe("error"); expect(sessionStorage.getItem("aisocialgame_token")).toBe("valid"); expect(mounts).toBe(0); api.me.mockResolvedValue({ id: "A", nickname: "Alice" }); await act(async () => { await auth.refreshUser(); }); expect(auth.user?.id).toBe("A"); });
it("clears expired sessions and ignores an earlier request that finishes late", async () => { sessionStorage.setItem("aisocialgame_token", "expired"); let complete!: (user: unknown) => void; api.me.mockImplementation(() => new Promise(resolve => { complete = resolve; })); await render(); act(() => api.expired()); await act(async () => { complete({ id: "A", nickname: "Late" }); }); expect(auth.status).toBe("anonymous"); expect(auth.user).toBeNull(); expect(sessionStorage.getItem("aisocialgame_token")).toBeNull(); expect(mounts).toBe(0); });
it("keeps the authenticated session if logout fails, and clears it only after success", async () => { sessionStorage.setItem("aisocialgame_token", "valid"); api.me.mockResolvedValue({ id: "A", nickname: "Alice" }); await render(); api.logout.mockRejectedValue(new Error("network")); await act(async () => { await auth.logout(); }); expect(auth.user?.id).toBe("A"); api.logout.mockResolvedValue(undefined); await act(async () => { await auth.logout(); }); expect(auth.user).toBeNull(); expect(element.textContent).not.toContain("PRIVATE_DATA"); });
it.each(["https://outside.invalid", "//outside.invalid", "/\\outside.invalid", "/sso/callback", "/admin", "/%2f%2foutside.invalid", "/%61dmin", "/unknown", "/room/werewolf"])("rejects unsafe or flow return paths: %s", path => { expect(safeReturnTo(path)).toBe("/"); });
it("consumes the original local path including query and hash once", () => { sessionStorage.setItem(LOCAL_RETURN_TO_KEY, "/profile?tab=wallet#balance"); expect(consumeReturnTo()).toBe("/profile?tab=wallet#balance"); expect(consumeReturnTo()).toBe("/"); });
