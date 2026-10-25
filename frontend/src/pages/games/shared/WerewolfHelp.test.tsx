import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GameState } from "@/types";
import { WerewolfRulesHelp, WerewolfTurnHelp } from "./WerewolfHelp";

vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: "zh-CN" } }) }));
let container: HTMLDivElement, root: Root;
beforeEach(() => { (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div"); document.body.append(container); root = createRoot(container); });
afterEach(() => { act(() => root.unmount()); container.remove(); });

it.each(["side", "city"])("explains only the room's %s victory condition", winCondition => {
  act(() => root.render(<WerewolfRulesHelp rules={{ winCondition }} />));
  expect(container.textContent).toContain(winCondition === "city" ? "屠城" : "屠边");
  expect(container.textContent).not.toContain(winCondition === "city" ? "屠边" : "屠城");
  expect(container.textContent).toContain("平票或全部弃票");
  expect(container.textContent).toContain("原房间再来一局");
});

it("shows delayed AI ballot progress only in the public voting phase", () => {
  const state: GameState = { roomId: "room", gameId: "werewolf", phase: "DAY_VOTE", round: 1, players: [], logs: [], extra: { waitingForAi: true, pendingAiCount: 3 } };
  act(() => root.render(<WerewolfTurnHelp state={state} />));
  expect(container.textContent).toContain("还有 3 名 AI");
  act(() => root.render(<WerewolfTurnHelp state={{ ...state, phase: "NIGHT" }} />));
  expect(container.textContent).not.toContain("3 名 AI");
  expect(container.textContent).toContain("等待天亮");
});
