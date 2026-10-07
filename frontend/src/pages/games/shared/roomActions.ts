import type { GameState, LegalAction } from "@/types";
import { parseLegalActions } from "./v2Actions";
import type { RoomText, RoomTextKey } from "./roomText";

export function roomActions(state: GameState): LegalAction[] {
  if (Number(state.extra?.ruleVersion) === 2) return parseLegalActions(state.extra?.legalActions);
  const me = state.players.find(p => p.playerId === state.myPlayerId);
  if (!me?.alive || ["WAITING", "SETTLEMENT"].includes(state.phase)) return [];
  const others = state.players.filter(p => p.alive && p.playerId !== state.myPlayerId).map(p => p.playerId);
  const action = (type: LegalAction["type"], targets: string[] = [], maxLength = 0, nightAction?: string): LegalAction => ({ type, label: type, targets, maxLength, nightAction });
  if (["VOTING", "DAY_VOTE"].includes(state.phase)) return state.votes?.[state.myPlayerId!] ? [] : [action("VOTE", others), action("SKIP")];
  if (["DESCRIPTION", "DAY_DISCUSS"].includes(state.phase) && me.seatNumber === state.currentSeat) return [action("SPEAK", [], 1000)];
  if (state.phase === "QUESTIONING") return [action("ASK_QUESTION", [], 1000), action("SUBMIT_SOLUTION", [], 1000)];
  if (state.phase === "NIGHT" && state.pendingAction) {
    if (state.pendingAction.type === "WITCH") return [action("NIGHT_ACTION", [], 0, "WITCH_SAVE"), action("NIGHT_ACTION", others, 0, "WITCH_POISON"), action("SKIP")];
    return [action("NIGHT_ACTION", others, 0, state.pendingAction.type)];
  }
  return [];
}

export function actionLabel(action: LegalAction, copy: RoomText): string {
  const labels: Record<string, RoomTextKey> = { SPEAK: "speak", ASK_PLAYER: "askPlayer", ANSWER_PLAYER: "answer", DISCUSS: "discuss",
    ASK_QUESTION: "askHost", SUBMIT_SOLUTION: "solution", VOTE: "vote", SKIP: "skip", HUNTER_SHOOT: "shoot",
    REQUEST_HINT: "hint", REVEAL_SOLUTION: "reveal", WOLF_KILL: "wolfKill", SEER_CHECK: "seerCheck", GUARD_PROTECT: "guard",
    WITCH_SAVE: "heal", WITCH_POISON: "poison", WEREWOLF: "wolfKill", SEER: "seerCheck", GUARD: "guard" };
  const key = labels[action.nightAction || action.type];
  return key ? copy(key) : action.label;
}
