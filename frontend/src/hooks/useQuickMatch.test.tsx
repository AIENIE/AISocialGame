import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useQuickMatch } from "./useQuickMatch";
import { quickMatchApi, type QuickMatchResult } from "@/services/v2Social";
import { toast } from "sonner";

const navigate = vi.hoisted(() => vi.fn());
vi.mock("react-router-dom", () => ({ useNavigate: () => navigate }));
vi.mock("@/services/v2Social", () => ({ quickMatchApi: { start: vi.fn() } }));
vi.mock("sonner", () => ({ toast: { warning: vi.fn(), error: vi.fn(), info: vi.fn() } }));
vi.mock("react-i18next", async importOriginal => ({ ...await importOriginal<typeof import("react-i18next")>(), useTranslation: () => ({ t: (key: string, values?: { reason?: string }) => `${key}${values?.reason ? `: ${values.reason}` : ""}` }) }));
let current: ReturnType<typeof useQuickMatch>, root: Root, container: HTMLDivElement;
function Harness() { current = useQuickMatch(); return <button disabled={current.matching} onClick={() => void current.start("werewolf", "玩家")}>开始</button>; }
beforeEach(() => { vi.clearAllMocks(); (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div"); document.body.append(container); root = createRoot(container); act(() => root.render(<Harness />)); });
afterEach(() => { act(() => root.unmount()); container.remove(); });

it("deduplicates same-frame clicks and keeps the entry disabled until the request completes", async () => {
  let finish!: (result: QuickMatchResult) => void;
  vi.mocked(quickMatchApi.start).mockReturnValue(new Promise(resolve => { finish = resolve; }));
  act(() => { void current.start("werewolf", "玩家"); void current.start("werewolf", "玩家"); });
  expect(quickMatchApi.start).toHaveBeenCalledOnce();
  expect(container.querySelector("button")!.disabled).toBe(true);
  await act(async () => finish({ roomId: "kept", autoStarted: true }));
  expect(navigate).toHaveBeenCalledExactlyOnceWith("/room/werewolf/kept", { state: null });
  expect(container.querySelector("button")!.disabled).toBe(false);
});

it("opens the saved room with visible recovery feedback instead of offering a new creation", async () => {
  vi.mocked(quickMatchApi.start).mockResolvedValue({ roomId: "kept", autoStarted: false, failure: "测试补位失败" });
  await act(async () => current.start("werewolf", "玩家"));
  expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining("测试补位失败"));
  expect(navigate).toHaveBeenCalledWith("/room/werewolf/kept", { state: { quickStartNotice: expect.stringContaining("测试补位失败") } });
});
