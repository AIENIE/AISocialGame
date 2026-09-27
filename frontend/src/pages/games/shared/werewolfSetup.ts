export const WEREWOLF_ROLE_LABELS: Record<string, string> = {
  WEREWOLF: "狼人", SEER: "预言家", WITCH: "女巫", HUNTER: "猎人",
  IDIOT: "白痴", GUARD: "守卫", VILLAGER: "村民",
};

/** Mirrors the server's supported setups; final room rules come from its public view. */
export function werewolfBoardRoles(template: string, count: number): Record<string, number> {
  if (![6, 9, 12].includes(count) || !["standard", "guard", "no_god"].includes(template)) return {};
  const roles: Record<string, number> = { WEREWOLF: count / 3 };
  if (template !== "no_god") {
    roles.SEER = 1;
    if (template === "standard") {
      roles.WITCH = 1;
      if (count >= 9) roles.HUNTER = 1;
      if (count === 12) roles.IDIOT = 1;
    } else {
      roles.GUARD = 1;
      if (count >= 9) roles.WITCH = 1;
      if (count === 12) roles.HUNTER = 1;
    }
  }
  roles.VILLAGER = count - Object.values(roles).reduce((sum, value) => sum + value, 0);
  return roles;
}
