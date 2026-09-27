import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { GameState, LegalAction } from "@/types";
import { V2ActionPanel } from "./V2ActionPanel";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ i18n: { language: "zh-CN" }, t: (key: string) => key }) }));

let container: HTMLDivElement;
let root: Root;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); container.remove(); });

const state = (actions: LegalAction[], phaseToken = "instance:1"): GameState => ({ roomId: "room", gameId: "turtle_soup", phase: "QUESTIONING", round: 1, players: [
  { playerId: "p1", displayName: "小林", seatNumber: 0, alive: true, ai: false },
  { playerId: "p2", displayName: "阿棠", seatNumber: 1, alive: true, ai: true },
], logs: [], extra: { ruleVersion: 2, phaseToken, legalActions: actions } });

describe("V2ActionPanel", () => {
  it("shows only the current legal actions and sends an explicit witch save", () => {
    const save: LegalAction = { type: "NIGHT_ACTION", label: "使用解药", nightAction: "WITCH_SAVE", targets: ["p2"], maxLength: 120 };
    const submit = vi.fn();
    act(() => root.render(<V2ActionPanel state={state([save])} onAction={submit} />));
    expect(container.querySelectorAll("option").length).toBe(2);
    expect(container.textContent).not.toContain("使用毒药");
    act(() => container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
    expect(submit).toHaveBeenCalledWith({ type: "NIGHT_ACTION", nightAction: "WITCH_SAVE", targetPlayerId: "p2", useHeal: true });
  });

  it("clears stale action drafts on phase changes and disables submission while pending", () => {
    const ask: LegalAction = { type: "ASK_QUESTION", label: "向主持提问", targets: [], maxLength: 1000 };
    const submit = vi.fn();
    act(() => root.render(<V2ActionPanel state={state([ask])} onAction={submit} />));
    const input = container.querySelector("textarea")!;
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!.call(input, "他故意留下钥匙吗？");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(input.value).toBe("他故意留下钥匙吗？");
    act(() => root.render(<V2ActionPanel state={state([ask], "instance:2")} pending onAction={submit} />));
    expect(container.querySelector("textarea")!.value).toBe("");
    expect(container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.disabled).toBe(true);
    act(() => container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
    expect(submit).not.toHaveBeenCalled();
  });

  it("requires a deliberate confirmation before revealing a shared solution", () => {
    const reveal: LegalAction = { type: "REVEAL_SOLUTION", label: "揭示汤底", targets: [], maxLength: 0 };
    const submit = vi.fn();
    act(() => root.render(<V2ActionPanel state={state([reveal])} onAction={submit} />));
    act(() => container.querySelector<HTMLButtonElement>('[data-testid="v2-reveal-solution"]')!.click());
    expect(submit).not.toHaveBeenCalled();
    const confirmation = [...document.querySelectorAll("button")].find((button) => button.textContent === "结束并揭示汤底")!;
    act(() => confirmation.click());
    expect(submit).toHaveBeenCalledWith({ type: "REVEAL_SOLUTION" });
  });

  it("renders a quiet waiting state for observers with no capabilities", () => {
    act(() => root.render(<V2ActionPanel state={state([])} onAction={vi.fn()} />));
    expect(container.querySelector("button")).toBeNull();
    expect(container.querySelector('[role="status"]')?.textContent).toContain("等待");
  });
});
