import type { GameState, Room } from "@/types";
import { roomText } from "./roomText";

export function roleName(role: string, language: string) {
  const names: Record<string, [string, string, string]> = {
    WEREWOLF: ["狼人", "狼人", "Werewolf"], SEER: ["预言家", "預言家", "Seer"], WITCH: ["女巫", "女巫", "Witch"],
    HUNTER: ["猎人", "獵人", "Hunter"], IDIOT: ["白痴", "白痴", "Idiot"], GUARD: ["守卫", "守衛", "Guard"],
    VILLAGER: ["村民", "村民", "Villager"], CIVILIAN: ["平民", "平民", "Majority"], UNDERCOVER: ["卧底", "臥底", "Undercover"], BLANK: ["白板", "白板", "Blank"],
    TURTLE_SOUP_PLAYER: ["解谜者", "解謎者", "Investigator"],
  };
  return names[role]?.[language.startsWith("en") ? 2 : /TW|HK|Hant/i.test(language) ? 1 : 0] || role;
}

export function privateLabel(state: GameState, room: Room, language: string) {
  const copy = roomText(language);
  if (room.config?.hostMode === "AUTHOR" && state.myPlayerId === room.hostUserId) return copy("author");
  if (state.gameId === "turtle_soup") return "";
  if (state.gameId === "undercover") return state.myRole === "BLANK" || state.extra?.blank ? copy("blank") : state.myWord || copy("unknownWord");
  return state.myRole ? roleName(state.myRole, language) : "";
}
