import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GameState, LegalAction, PlayerAction } from "@/types";
import { RoomActionDock } from "./RoomActionDock";
import { useRoomActionController } from "./useRoomActionController";
import { RoomPlayers } from "./RoomPlayers";

let language = "zh-CN";
vi.mock("react-i18next", () => ({ useTranslation: () => ({ i18n: { language }, t: (key: string) => key }) }));
const players = ["me", "p2", "p3"].map((id, index) => ({ playerId: id, displayName: id, seatNumber: index, ai: false, alive: true }));
const state = (legalActions: LegalAction[], phaseToken = "one", phase = "VOTING"): GameState => ({ roomId: "room", gameId: "undercover", phase, round: 1, myPlayerId: "me", players, logs: [], extra: { ruleVersion: 2, legalActions, phaseToken } });
let root: Root, container: HTMLDivElement;
beforeEach(() => { language = "zh-CN"; (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true; container = document.createElement("div"); document.body.append(container); root = createRoot(container); });
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
it("shows a preserved budget pause and offers no action submission",()=>{
  const send=vi.fn();const value=state([{type:"SPEAK",label:"描述",targets:[],maxLength:90}],"one","DESCRIPTION");
  act(()=>root.render(<Harness value={{...value,extra:{...value.extra,labBudgetPaused:true}}} send={send}/>));
  expect(container.textContent).toContain("当前局面和计时已保留");
  expect(container.querySelector("textarea")).toBeNull();expect(container.querySelector('[data-testid="v2-action-submit"]')).toBeNull();
  expect(send).not.toHaveBeenCalled();
  act(()=>root.render(<Harness value={{...value,extra:{...value.extra,labCallPaused:true}}} send={send}/>));
  expect(container.textContent).toContain("实验调用已暂停");
  expect(container.querySelector("textarea")).toBeNull();expect(container.querySelector('[data-testid="v2-action-submit"]')).toBeNull();
});

it("keeps a forbidden-word draft and shows private correction feedback until elimination",async()=>{
  const actions:LegalAction[]=[{type:"SPEAK",label:"描述",targets:[],maxLength:90}];
  const send=vi.fn().mockRejectedValue(new Error("correct"));
  const value=state(actions,"one","DESCRIPTION");
  act(()=>root.render(<Harness value={value} send={send}/>));fill("我的原文");
  await act(async()=>container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.click());
  act(()=>root.render(<Harness value={{...value,extra:{...value.extra,wordFeedback:{forbiddenTerms:["咖啡"],remainingCorrections:2,eliminated:false}}}} send={send}/>));
  expect(container.querySelector("textarea")!.value).toBe("我的原文");
  expect(container.textContent).toContain("咖啡");expect(container.textContent).toContain("还可修正 2 次");
  act(()=>root.render(<Harness value={{...value,extra:{...value.extra,legalActions:[],wordFeedback:{forbiddenTerms:["咖啡"],remainingCorrections:0,eliminated:true}}}} send={send}/>));
  expect(container.textContent).toContain("已判定出局");
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


it.each([
  ["zh-CN", "补充描述你的词语", "提交补充描述", "跳过补充描述"],
  ["zh-TW", "補充描述你的詞語", "提交補充描述", "跳過補充描述"],
  ["en", "Add a description of your word", "Submit extra description", "Skip extra description"],
])("shows word description controls for tied candidates in %s", async (locale, label, submit, skip) => {
  language = locale;
  const send = vi.fn().mockResolvedValue(undefined);
  const actions: LegalAction[] = [{ type: "SPEAK", label: "补充描述", targets: [], maxLength: 60 }, { type: "SKIP", label: "跳过", targets: [], maxLength: 0 }];
  act(() => root.render(<Harness value={state(actions, "tie", "TIE_DEFENSE")} send={send} />));
  expect(container.querySelector("textarea")!.getAttribute("aria-label")).toBe(label);
  expect(container.querySelector('[data-testid="v2-action-submit"]')!.textContent).toBe(submit);
  expect(container.querySelector('[data-testid="v2-action-skip"]')!.textContent).toBe(skip);
  expect(container.querySelector('[data-testid="room-choose-target"]')).toBeNull();
  fill("长".repeat(61));
  expect(container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.disabled).toBe(true);
  fill("下雨时我会用到它。");
  await act(async () => container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.click());
  expect(send).toHaveBeenCalledExactlyOnceWith({ type: "SPEAK", content: "下雨时我会用到它。" });
});

it.each(["zh-CN", "zh-TW", "en"])("confirms a self-destruct guess, preserves drafts on cancel and sends only once in %s", async locale => {
  language = locale;
  let resolve!: () => void;
  const send = vi.fn(() => new Promise<void>(done => { resolve = done; }));
  const actions: LegalAction[] = [{ type: "SPEAK", label: "描述", targets: [], maxLength: 90 }, { type: "SELF_DESTRUCT", label: "自爆猜词", targets: [], maxLength: 20 }];
  act(() => root.render(<Harness value={state(actions, "description", "DESCRIPTION")} send={send} />));
  const modes = container.querySelectorAll<HTMLButtonElement>('.room-action-modes button');
  expect(modes[0].getAttribute("aria-pressed")).toBe("true");
  act(() => modes[1].click());
  fill("长".repeat(21)); expect(container.querySelector<HTMLButtonElement>('[data-testid="v2-action-submit"]')!.disabled).toBe(true);
  fill("自行车"); click('[data-testid="v2-action-submit"]'); expect(send).not.toHaveBeenCalled();
  const dialog = () => document.querySelector('[role="alertdialog"]')!;
  expect(dialog().textContent).toContain("自行车");
  act(() => dialog().querySelector<HTMLButtonElement>('button')!.click());
  expect(document.querySelector('[role="alertdialog"]')).toBeNull(); expect(container.querySelector("textarea")!.value).toBe("自行车");
  click('[data-testid="v2-action-submit"]');
  const confirm = dialog().querySelectorAll<HTMLButtonElement>('button')[1];
  act(() => { confirm.click(); confirm.click(); });
  expect(send).toHaveBeenCalledExactlyOnceWith({ type: "SELF_DESTRUCT", content: "自行车" });
  await act(async () => resolve());
});

it("closes an unsubmitted self-destruct confirmation when the opportunity changes", () => {
  const send = vi.fn().mockResolvedValue(undefined);
  const actions: LegalAction[] = [{ type: "SELF_DESTRUCT", label: "自爆猜词", targets: [], maxLength: 20 }];
  act(() => root.render(<Harness value={state(actions)} send={send} />));
  fill("单车"); click('[data-testid="v2-action-submit"]');
  expect(document.querySelector('[role="alertdialog"]')).not.toBeNull();
  act(() => root.render(<Harness value={state([], "after-elimination")} send={send} />));
  expect(document.querySelector('[role="alertdialog"]')).toBeNull(); expect(send).not.toHaveBeenCalled();
});
