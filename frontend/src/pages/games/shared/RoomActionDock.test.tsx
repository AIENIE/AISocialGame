import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GameState, LegalAction, PlayerAction } from "@/types";
import { RoomActionDock } from "./RoomActionDock";
import { useRoomActionController } from "./useRoomActionController";
import { RoomPlayers } from "./RoomPlayers";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ i18n: { language: "zh-CN" }, t: (key: string) => key }) }));
const players = ["me", "p2", "p3"].map((id, index) => ({ playerId: id, displayName: id, seatNumber: index, ai: false, alive: true }));
const state = (legalActions: LegalAction[], phaseToken = "one", phase = "VOTING"): GameState => ({ roomId: "room", gameId: "undercover", phase, round: 1, myPlayerId: "me", players, logs: [], extra: { ruleVersion: 2, legalActions, phaseToken } });
let root: Root, container: HTMLDivElement;
beforeEach(() => { (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true; container = document.createElement("div"); document.body.append(container); root = createRoot(container); });
afterEach(() => { act(() => root.unmount()); container.remove(); vi.clearAllMocks(); });
function Harness({ value, send, connected = true }: { value: GameState; send: (action: PlayerAction) => Promise<unknown>; connected?: boolean }) {
  const control = useRoomActionController(value, send);
  return <><RoomPlayers players={players} targets={control.selected?.targets} selected={control.target} onSelect={control.selectTarget} pending={control.busy} />
    <RoomActionDock state={value} control={control} connected={connected} waitingText="等待" onChoosePlayer={vi.fn()} /></>;
}
const click = (selector: string) => act(() => container.querySelector<HTMLButtonElement>(selector)!.click());
const fill = (text: string) => act(() => {
  const textarea = container.querySelector("textarea")!;
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!.call(textarea, text);
  textarea.dispatchEvent(new Event("input", { bubbles: true }));
});

it("selects only server-authorized players and confirms a ballot once", async () => {
  let resolve!: () => void;
  const send = vi.fn(() => new Promise<void>(done => { resolve = done; }));
  act(() => root.render(<Harness value={state([{ type: "VOTE", targets: ["p2", "p3"], label: "投票", maxLength: 0 }])} send={send} />));
  click('[data-player-id="me"]');
  expect(container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.disabled).toBe(true);
  click('[data-player-id="p2"]');
  expect(send).not.toHaveBeenCalled();
  click('[data-testid="v2-action-submit"]'); click('[data-testid="v2-action-submit"]');
  expect(send).toHaveBeenCalledExactlyOnceWith({ type: "VOTE", targetPlayerId: "p2" });
  await act(async () => resolve());
  expect(container.textContent).toContain("已提交");
});

it("retains failed speech, clears obsolete drafts at a turn change and blocks offline submission", async () => {
  const send = vi.fn().mockRejectedValue(new Error("offline"));
  const actions: LegalAction[] = [{ type: "SPEAK", label: "描述", targets: [], maxLength: 90 }];
  act(() => root.render(<Harness value={state(actions, "one", "DESCRIPTION")} send={send} />));
  fill("我想再核对一下");
  await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.click());
  expect(container.querySelector("textarea")!.value).toBe("我想再核对一下");
  act(() => root.render(<Harness value={state(actions, "two", "DESCRIPTION")} send={send} connected={false} />));
  expect(container.querySelector("textarea")!.value).toBe("");
  expect(container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.disabled).toBe(true);
  expect(container.textContent).toContain("回合已更新");
});

it("allows guard self-protection and does not offer unknown targets", () => {
  act(() => root.render(<Harness value={state([{ type: "NIGHT_ACTION", nightAction: "GUARD_PROTECT", targets: ["me"], label: "守护", maxLength: 0 }], "one", "NIGHT")} send={vi.fn()} />));
  expect(container.querySelector('[data-player-id="me"]')!.getAttribute("aria-pressed")).toBe("true");
  expect(container.querySelector('[data-player-id="p2"]')!.getAttribute("aria-disabled")).toBe("true");
});

it("requires an explicit confirmation for the final soup answer", async () => {
  const send = vi.fn().mockResolvedValue(undefined);
  const value = { ...state([{ type: "SUBMIT_SOLUTION", label: "解答", targets: [], maxLength: 1000 }], "one", "FINAL_ANSWER"), gameId: "turtle_soup" };
  act(() => root.render(<Harness value={value} send={send} />)); fill("我们的最终推理");
  click('[data-testid="v2-action-submit"]');
  expect(send).not.toHaveBeenCalled();
  expect(document.querySelector('[role="alertdialog"]')?.textContent).toContain("答错将结束本局");
  const confirm = [...document.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')].find(button => button.textContent === "确认提交")!;
  await act(async () => confirm.click());
  expect(send).toHaveBeenCalledExactlyOnceWith({ type: "SUBMIT_SOLUTION", content: "我们的最终推理" });
});
