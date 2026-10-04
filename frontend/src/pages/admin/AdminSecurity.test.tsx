import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AdminSecurity from "./AdminSecurity";
const state = vi.hoisted(() => ({ get: vi.fn(), me: vi.fn() }));
vi.mock("@/hooks/useAdminAuth", () => ({ useAdminAuth: () => ({ admin: { authMode: "password", recoveryCodesRemaining: 9 } }) }));
vi.mock("@/services/api", () => ({ adminApi: { me: () => state.me(), getRecoveryCodes: (code: string) => state.get(code) }, getApiErrorMessage: () => "failed" }));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
let root: Root, container: HTMLDivElement;
beforeEach(() => {
  Reflect.set(globalThis,"IS_REACT_ACT_ENVIRONMENT",true);
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
  state.get.mockReset(); state.me.mockReset(); state.me.mockResolvedValue({ recoveryCodesRemaining: 9 });
});
afterEach(() => { act(() => root.unmount()); container.remove(); });
async function click(text: string) { await act(async () => { Array.from(container.querySelectorAll("button")).find(button => button.textContent===text)?.click(); }); }
it("clears displayed codes when closed, on failed retrieval and when leaving", async () => {
  await act(async () => root.render(<AdminSecurity />));
  state.get.mockResolvedValue({ recoveryCodes: ["TEST_EMERGENCY_SECRET"], generatedCount: 1, remaining: 10 });
  await click("获取紧急码");
  expect(state.get).toHaveBeenCalledWith(""); expect(container.textContent).toContain("TEST_EMERGENCY_SECRET");
  await click("关闭紧急码"); expect(container.textContent).not.toContain("TEST_EMERGENCY_SECRET");
  await click("获取紧急码"); expect(container.textContent).toContain("TEST_EMERGENCY_SECRET");
  state.get.mockRejectedValue(new Error("denied")); await click("获取紧急码"); expect(container.textContent).not.toContain("TEST_EMERGENCY_SECRET");
  await act(async () => root.render(<div>left</div>)); expect(container.textContent).not.toContain("TEST_EMERGENCY_SECRET");
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
});
