import i18n from "./config";
import { GAME_CONFIG_KEYS, GAME_DISPLAY_KEYS } from "@/config/games";

/**
 * 游戏目录展示文案解析：
 * 以稳定 game.id（werewolf / undercover / turtle_soup）映射到 resources 中的 t key，
 * 供组件在渲染后端/本地游戏数据时取本地化文案；未知 id 回退到原始数据。
 * 注意：模块被导入时 i18n 已初始化（config.ts 顶层 init），可安全同步调用 i18n.t。
 */

export function gameName(id: string | undefined, fallback = ""): string {
  const keys = id ? GAME_DISPLAY_KEYS[id] : undefined;
  return keys ? i18n.t(keys.name) : fallback;
}

export function gameDescription(id: string | undefined, fallback = ""): string {
  const keys = id ? GAME_DISPLAY_KEYS[id] : undefined;
  return keys ? i18n.t(keys.desc) : fallback;
}

export function gameTags(id: string | undefined, fallback: string[] = []): string[] {
  const keys = id ? GAME_DISPLAY_KEYS[id] : undefined;
  return keys ? keys.tags.map((key) => i18n.t(key)) : fallback;
}

/** configSchema 字段 label：未知 game/field 回退原始中文 label（zh-CN 兜底）。 */
export function gameFieldLabel(gameId: string | undefined, fieldId: string, fallback = ""): string {
  if (fieldId === "aiDifficulty") return i18n.language.startsWith("en") ? "AI difficulty" : i18n.language === "zh-TW" ? "AI 難度" : "AI 难度";
  const fieldKeys = gameId ? GAME_CONFIG_KEYS[gameId]?.[fieldId] : undefined;
  return fieldKeys ? i18n.t(fieldKeys.label) : fallback;
}

/** configSchema 选项 label：以 option.value 字符串化匹配，未知回退原始中文 label。 */
export function gameOptionLabel(
  gameId: string | undefined,
  fieldId: string,
  optionValue: string | number | boolean,
  fallback = "",
): string {
  if (fieldId === "aiDifficulty") { const labels = i18n.language.startsWith("en") ? ["Simple", "Social", "Advanced"] : i18n.language === "zh-TW" ? ["簡單", "娛樂", "進階"] : ["简单", "娱乐", "进阶"]; return labels[Number(optionValue) - 1] || fallback; }
  if (gameId === "turtle_soup" && fieldId === "caseId" && optionValue === "random") return i18n.language.startsWith("en") ? "Random puzzle" : i18n.language === "zh-TW" ? "隨機題目" : "随机题目";
  const fieldKeys = gameId ? GAME_CONFIG_KEYS[gameId]?.[fieldId] : undefined;
  const key = fieldKeys?.options?.[String(optionValue)];
  return key ? i18n.t(key) : fallback;
}
