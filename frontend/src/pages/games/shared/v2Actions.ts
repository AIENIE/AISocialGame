import type { LegalAction, PlayerAction } from "@/types";

const ACTION_TYPES = new Set<PlayerAction["type"]>([
  "SPEAK", "VOTE", "NIGHT_ACTION", "ASK_QUESTION", "SUBMIT_SOLUTION", "ASK_PLAYER", "ANSWER_PLAYER",
  "SKIP", "HUNTER_SHOOT", "DISCUSS", "REQUEST_HINT", "REVEAL_SOLUTION",
]);
const TEXT_ACTIONS = new Set<PlayerAction["type"]>(["SPEAK", "ASK_PLAYER", "ANSWER_PLAYER", "DISCUSS", "ASK_QUESTION", "SUBMIT_SOLUTION"]);

export const actionKey = (action: LegalAction) => `${action.type}:${action.nightAction || ""}`;
export const needsText = (action: LegalAction) => TEXT_ACTIONS.has(action.type);

export function parseLegalActions(value: unknown): LegalAction[] {
  if (!Array.isArray(value)) return [];
  const keys = new Set<string>();
  return value.flatMap((item): LegalAction[] => {
    if (!item || typeof item !== "object" || !ACTION_TYPES.has(item.type)
      || typeof item.label !== "string" || !Array.isArray(item.targets)
      || typeof item.maxLength !== "number" || !Number.isInteger(item.maxLength) || item.maxLength < 0 || item.maxLength > 1000
      || (item.nightAction != null && typeof item.nightAction !== "string")) return [];
    if (!item.targets.every((id: unknown) => typeof id === "string" && id.length > 0)) return [];
    const action: LegalAction = { type: item.type, label: item.label, nightAction: item.nightAction ?? null,
      targets: [...new Set<string>(item.targets)], maxLength: item.maxLength };
    if (action.type === "NIGHT_ACTION" && !action.nightAction) return [];
    if (keys.has(actionKey(action))) return [];
    keys.add(actionKey(action));
    return [action];
  });
}

/** UI guardrails only. The server independently checks capabilities and the phase token. */
export function buildPlayerAction(capability: LegalAction, input: { content?: string; target?: string; abstain?: boolean },
                                  capabilities: LegalAction[]): PlayerAction | null {
  const current = capabilities.find((candidate) => actionKey(candidate) === actionKey(capability));
  if (!current) return null;
  const content = input.content?.trim() || "";
  if ((needsText(current) && !content) || Array.from(content).length > current.maxLength) return null;
  if (current.type === "VOTE" && input.abstain) {
    if (!capabilities.some((candidate) => candidate.type === "SKIP")) return null;
    return { type: "VOTE", abstain: true, ...(content ? { content } : {}) };
  }
  if (current.targets.length > 0 && !current.targets.includes(input.target || "")) return null;
  const action: PlayerAction = { type: current.type };
  if (content) action.content = content;
  if (current.targets.length > 0) action.targetPlayerId = input.target;
  if (current.nightAction) action.nightAction = current.nightAction;
  if (current.nightAction === "WITCH_SAVE") action.useHeal = true;
  return action;
}
