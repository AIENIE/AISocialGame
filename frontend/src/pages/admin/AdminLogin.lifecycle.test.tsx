import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AdminLogin from "./AdminLogin";
import type { AdminAuthResponse, AdminEnrollmentStart, AdminLoginResult } from "@/types";
const state = vi.hoisted(() => ({
  navigate: vi.fn(), policy: vi.fn(), login: vi.fn(), startEnrollment: vi.fn(), confirmEnrollment: vi.fn(),
  verifyTotp: vi.fn(), verifyRecovery: vi.fn(), startRebind: vi.fn(), confirmRebind: vi.fn(), logout: vi.fn(),
  admin: null as AdminAuthResponse | null,
}));
vi.mock("react-router-dom", () => ({ useNavigate: () => state.navigate }));
vi.mock("@/hooks/useAdminAuth", () => ({ useAdminAuth: () => ({ ...state, loading: false }) }));
vi.mock("@/services/api", () => ({ adminApi: { policy: () => state.policy(), recoveryChallenge: () => state.login() }, getApiErrorMessage: () => "failed" }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
let root: Root, container: HTMLDivElement;
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
const setup = (secret: string): AdminEnrollmentStart => ({ challengeId: "challenge", manualKey: secret, otpauthUri: "uri", expiresAt: "later" });
const authenticated = (codes: string[]): AdminLoginResult => ({ state: "AUTHENTICATED", sessionScope: "FULL", recoveryCodes: codes });
beforeEach(() => {
  Reflect.set(globalThis,"IS_REACT_ACT_ENVIRONMENT",true);
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
  state.admin = null;
  for (const fn of [state.navigate,state.policy,state.login,state.startEnrollment,state.confirmEnrollment,state.verifyTotp,state.verifyRecovery,state.startRebind,state.confirmRebind,state.logout]) fn.mockReset();
  state.policy.mockResolvedValue({ env: "local", authMode: "totp" }); state.logout.mockResolvedValue(undefined);
});
afterEach(() => { act(() => root.unmount()); container.remove(); });
async function render() { await act(async () => root.render(<AdminLogin />)); }
async function submit() { await act(async () => container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))); }
async function cyclePage() { await act(async () => { window.dispatchEvent(new Event("pagehide")); window.dispatchEvent(new Event("pageshow")); }); }
async function click(text: string) { await act(async () => Array.from(container.querySelectorAll("button")).find(button => button.textContent === text)?.click()); }
it("rejects a late enrollment seed and allows a new enrollment after pageshow", async () => {
  await render();
  state.login.mockResolvedValue({ state: "ENROLLMENT_REQUIRED", challengeId: "challenge" });
  const old = deferred<AdminEnrollmentStart>(); state.startEnrollment.mockReturnValueOnce(old.promise);
  await submit(); await cyclePage();
  await act(async () => old.resolve(setup("OLD_ENROLLMENT_SEED")));
  expect(container.textContent).not.toContain("OLD_ENROLLMENT_SEED");
  state.startEnrollment.mockResolvedValue(setup("FRESH_ENROLLMENT_SEED"));
  await submit(); expect(container.textContent).toContain("FRESH_ENROLLMENT_SEED");
});
it("rejects late first-binding codes and clears completed codes on pageshow", async () => {
  await render(); state.login.mockResolvedValue({ state: "ENROLLMENT_REQUIRED", challengeId: "challenge" }); state.startEnrollment.mockResolvedValue(setup("seed"));
  await submit(); const old = deferred<AdminLoginResult>(); state.confirmEnrollment.mockReturnValueOnce(old.promise);
  await submit(); await cyclePage(); await act(async () => old.resolve(authenticated(["OLD_CODES_SECRET"])));
  expect(container.textContent).not.toContain("OLD_CODES_SECRET");
  await submit(); state.confirmEnrollment.mockResolvedValue(authenticated(["FRESH_CODES_SECRET"])); await submit();
  expect(container.textContent).toContain("FRESH_CODES_SECRET");
  await cyclePage(); expect(container.textContent).not.toContain("FRESH_CODES_SECRET");
});
it("ignores a resumed rebind seed from before pagehide and permits a new resume", async () => {
  state.admin = { username: "admin", displayName: "admin", sessionScope: "RECOVERY_REBIND_ONLY", authMode: "totp", expiresAt: "later" };
  const old = deferred<AdminEnrollmentStart>(); state.startRebind.mockReturnValueOnce(old.promise).mockResolvedValue(setup("FRESH_REBIND_SEED"));
  await render(); await cyclePage(); await act(async () => old.resolve(setup("OLD_REBIND_SEED")));
  expect(container.textContent).not.toContain("OLD_REBIND_SEED"); expect(container.textContent).toContain("FRESH_REBIND_SEED");
});
it("cancellation invalidates an in-flight confirmation and prevents its navigation", async () => {
  state.admin = { username: "admin", displayName: "admin", sessionScope: "RECOVERY_REBIND_ONLY", authMode: "totp", expiresAt: "later" };
  state.startRebind.mockResolvedValue(setup("seed"));
  await render(); const old = deferred<AdminLoginResult>(); state.confirmRebind.mockReturnValueOnce(old.promise);
  await submit(); state.logout.mockImplementation(() => { state.admin = null; return Promise.resolve(); });
  await click("取消并重新登录"); await act(async () => old.resolve(authenticated([])));
  expect(state.navigate).not.toHaveBeenCalled(); expect(container.querySelector("code.break-all")).toBeNull();
});
it("an old password response cannot navigate after pagehide", async () => {
  await render(); const old = deferred<AdminLoginResult>(); state.login.mockReturnValueOnce(old.promise);
  await submit(); await cyclePage(); await act(async () => old.resolve(authenticated([])));
  expect(state.navigate).not.toHaveBeenCalled();
});
