import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AxiosError } from "axios";
import Replays from "./Replays";
import ReplayPlayer from "./ReplayPlayer";
const state = vi.hoisted(() => ({ query: {} as any, language: "zh-CN", queryFn: null as any, local: vi.fn() }));
vi.mock("@tanstack/react-query", () => ({ useQuery: (options: any) => { state.queryFn = options.queryFn; return { refetch: vi.fn(), ...state.query }; } }));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ user: { id: "self" }, displayName: "Self" }) }));
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: state.language } }) }));
vi.mock("@/services/api", () => ({ serverReplayApi: { my: vi.fn(), list: vi.fn(), events: vi.fn() } }));
vi.mock("@/services/v2Social", () => ({ replayApi: { list: () => [{ id: "a", roomName: "LOCAL_SECRET", events: [], createdAt: "2026-01-01" }], get: (...args: any[]) => state.local(...args) } }));
let root: Root, container: HTMLDivElement;
beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
  state.query = {}; state.language = "zh-CN"; state.local.mockReset();
});
afterEach(() => { act(() => root.unmount()); container.remove(); });
function render(player = false) { act(() => root.render(<MemoryRouter initialEntries={["/replay/a"]}><Routes><Route path="/replay/:archiveId" element={player ? <ReplayPlayer /> : <Replays />} /></Routes></MemoryRouter>)); }
it.each([401, 403])("never shows cached data or local fallback after %s", status => {
  const error = new AxiosError("denied"); error.response = { status } as any;
  state.query = { isError: true, error, data: { items: [{ id: "a", roomName: "CACHED_SECRET" }], archive: { id: "a", roomName: "CACHED_SECRET" }, events: [] } };
  render(); expect(container.textContent).not.toMatch(/LOCAL_SECRET|CACHED_SECRET/);
  render(true); expect(container.textContent).not.toMatch(/LOCAL_SECRET|CACHED_SECRET/); expect(state.local).not.toHaveBeenCalled();
});
it("never uses local storage after server failure or an empty list", () => {
  state.query = { data: { items: [], total: 0 } }; render(); expect(container.textContent).not.toContain("LOCAL_SECRET");
  state.query = { isError: true, error: new Error("network") }; render(); expect(container.textContent).not.toContain("LOCAL_SECRET"); expect(container.textContent).toContain("data.failed");
});
it("renders only authorized views and reveals settlement text only at its timeline position", () => {
  state.query = { data: { archive: { id: "a", roomName: "archive" }, availableViews: ["PUBLIC", "PLAYER"], events: [
    { id: 1, seq: 1, phase: "DESCRIPTION", eventType: "SPEAK", data: { message: "first statement" } },
    { id: 2, seq: 2, phase: "SETTLEMENT", eventType: "REVEAL", data: { message: "FINAL_IDENTITY" } },
  ] } };
  render(true); expect(container.textContent).toContain("replay.view.PLAYER"); expect(container.textContent).not.toContain("replay.view.GOD"); expect(container.textContent).not.toContain("FINAL_IDENTITY");
  act(() => Array.from(container.querySelectorAll("button")).find(b => b.textContent?.includes("SETTLEMENT"))!.click());
  expect(container.textContent).toContain("FINAL_IDENTITY");
});
it.each(["zh-CN", "zh-TW", "en"])("localizes replay filters in %s", language => {
  state.language = language; render(); expect(container.querySelectorAll('input[type="datetime-local"]')).toHaveLength(2);
  expect(container.textContent).toContain(language === "en" ? "Replay scope" : language === "zh-TW" ? "回放範圍" : "回放范围");
});

it("does not fabricate events or timestamps for an empty archive", () => {
  state.query = { data: { archive: { id: "empty", roomName: "Empty" }, events: [], availableViews: ["PUBLIC"] } };
  render(true); expect(container.textContent).toContain("0/0"); expect(container.textContent).not.toContain("Invalid Date");
  const step = Array.from(container.querySelectorAll("button")).find(button => button.textContent?.includes("replay.step"));
  expect(step?.disabled).toBe(true);
});
