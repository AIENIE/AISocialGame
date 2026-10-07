import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GameLogEntry } from "@/types";
import { GameTimeline } from "./GameTimeline";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ i18n: { language: "zh-CN" } }) }));
let container: HTMLDivElement, root: Root, height = 1000;
const event = (id: string, fields: Partial<GameLogEntry> = {}): GameLogEntry => ({ type: "SPEECH", actorId: "p1", message: "original", time: "2026-10-07T12:00:00", metadata: { eventId: id, content: id }, ...fields });
beforeEach(() => { (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true; height = 1000; container = document.createElement("div"); document.body.append(container); root = createRoot(container);
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(() => height); vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(200);
});
afterEach(() => { act(() => root.unmount()); container.remove(); vi.restoreAllMocks(); });
const render = (logs: GameLogEntry[]) => act(() => root.render(<GameTimeline logs={logs} players={[{ playerId: "p1", displayName: "玩家", seatNumber: 0, ai: true, alive: true }]} onLoadOlder={vi.fn()} onRetry={vi.fn()} />));

it("keeps an older reading position and offers a jump to new messages", () => {
  render([event("one"), event("two")]);
  const scroll = container.querySelector<HTMLDivElement>('[data-testid="game-timeline"]')!;
  act(() => { scroll.scrollTop = 100; scroll.dispatchEvent(new Event("scroll")); });
  height = 1100; render([event("one"), event("two"), event("three")]);
  expect(scroll.scrollTop).toBe(100);
  expect(container.textContent).toContain("有新发言");
  act(() => container.querySelector<HTMLButtonElement>("button")!.click());
  expect(scroll.scrollTop).toBe(1100);
});

it("anchors content when older records are prepended", () => {
  render([event("two")]); const scroll = container.querySelector<HTMLDivElement>('[data-testid="game-timeline"]')!;
  act(() => { scroll.scrollTop = 50; scroll.dispatchEvent(new Event("scroll")); });
  height = 1300; render([event("one"), event("two")]);
  expect(scroll.scrollTop).toBe(350);
});

it("quotes the paired question and renders a vote result outside chat bubbles", () => {
  render([event("q", { type: "ASK_PLAYER", metadata: { eventId: "q", content: "问题正文", correlationId: "q" } }),
    event("a", { type: "ANSWER_PLAYER", metadata: { eventId: "a", content: "回答正文", correlationId: "q", presentation: { gesture: "nod" } } }),
    event("vote", { type: "VOTE_REVEAL", message: "投票已揭晓" })]);
  expect(container.querySelector("blockquote")?.textContent).toContain("问题正文");
  expect(container.querySelector('[data-testid="ai-presentation"]')?.textContent).toContain("轻轻点头");
  expect(container.querySelector('[data-testid="game-system-event"]')?.textContent).toBe("投票已揭晓");
  expect(container.querySelectorAll(".room-bubble")).toHaveLength(2);
});
