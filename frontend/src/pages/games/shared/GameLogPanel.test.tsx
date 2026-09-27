import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { gameplayApi } from "@/services/api";
import { GameLogPanel } from "./GameLogPanel";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: "en" } }) }));
vi.mock("@/services/api", () => ({ gameplayApi: { logs: vi.fn() } }));

let container: HTMLDivElement, root: Root, client: QueryClient;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); client.clear(); container.remove(); vi.clearAllMocks(); });

it("renders at most one public log page and moves through the public cursor", async () => {
  vi.mocked(gameplayApi.logs).mockImplementation(async (_game, _room, before) => before === undefined
    ? { items: [{ type: "speech", message: "latest", time: "2026-09-27T10:00:00" }], nextCursor: 11, hasMore: true }
    : { items: [{ type: "speech", message: "older", time: "2026-09-27T09:00:00" }], nextCursor: null, hasMore: false });
  act(() => { root.render(<QueryClientProvider client={client}><GameLogPanel gameId="werewolf" roomId="room" archiveId="one" logs={[]} emptyText="empty" /></QueryClientProvider>); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); });
  expect(container.textContent).toContain("latest");
  act(() => { container.querySelectorAll<HTMLButtonElement>("button")[0].click(); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); });
  expect(gameplayApi.logs).toHaveBeenLastCalledWith("werewolf", "room", 11);
  expect(container.textContent).toContain("older");
  expect(container.textContent).not.toContain("latest");
  act(() => { container.querySelectorAll<HTMLButtonElement>("button")[1].click(); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); });
  expect(container.textContent).toContain("latest");
});

it("mounts only visible log rows when a full page is scrolled", async () => {
  vi.mocked(gameplayApi.logs).mockResolvedValue({
    items: Array.from({ length: 100 }, (_, index) => ({ type: "speech", message: `log-${index}`, time: "2026-09-27T10:00:00" })),
    nextCursor: null, hasMore: false,
  });
  act(() => { root.render(<QueryClientProvider client={client}><GameLogPanel gameId="werewolf" roomId="room" archiveId="one" emptyText="empty" /></QueryClientProvider>); });
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); });
  expect(container.querySelectorAll("[data-testid='game-log-item']").length).toBeLessThan(20);
  expect(container.textContent).toContain("log-0");
  const viewport = container.querySelector<HTMLDivElement>("[data-testid='game-logs-scroll']")!;
  act(() => { viewport.scrollTop = 3000; viewport.dispatchEvent(new Event("scroll", { bubbles: true })); });
  expect(container.querySelectorAll("[data-testid='game-log-item']").length).toBeLessThan(20);
  expect(container.textContent).toContain("log-60");
  expect(container.textContent).not.toContain("log-0");
});
