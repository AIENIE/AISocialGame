// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import i18n from "@/i18n/config";
import {
  gameDescription,
  gameFieldLabel,
  gameName,
  gameOptionLabel,
  gameTags,
} from "@/i18n/gameTexts";
import { localizeErrorMessage } from "@/i18n/errors";

beforeEach(async () => {
  await i18n.changeLanguage("zh-CN");
});

afterEach(async () => {
  await i18n.changeLanguage("zh-CN");
});

describe("游戏目录展示文案映射（稳定 game.id）", () => {
  it("已知游戏 id 返回本地化 name/desc/tags", () => {
    expect(gameName("werewolf")).toBe("狼人杀");
    expect(gameName("undercover")).toBe("谁是卧底");
    expect(gameName("turtle_soup")).toBe("海龟汤");
    expect(gameDescription("werewolf")).toContain("天黑请闭眼");
    expect(gameTags("werewolf")).toEqual(["逻辑推理", "社交", "硬核"]);
  });

  it("随语言切换返回对应语言", async () => {
    await i18n.changeLanguage("en");
    expect(gameName("werewolf")).toBe("Werewolf");
    expect(gameTags("undercover")).toContain("Party");
    await i18n.changeLanguage("zh-TW");
    expect(gameName("turtle_soup")).toBe("海龜湯");
    expect(gameDescription("undercover")).toContain("臥底");
  });

  it("未知游戏 id 回退 fallback 原始数据", () => {
    expect(gameName("unknown_game", "神秘游戏")).toBe("神秘游戏");
    expect(gameTags("unknown_game", ["a"])).toEqual(["a"]);
  });
});

describe("configSchema 字段/选项文案映射", () => {
  it("已知字段 label 本地化", async () => {
    expect(gameFieldLabel("werewolf", "witchRule")).toBe("女巫规则");
    await i18n.changeLanguage("en");
    expect(gameFieldLabel("werewolf", "witchRule")).toBe("Witch rules");
  });

  it("选项 label 按 value 映射", async () => {
    expect(gameOptionLabel("werewolf", "template", "standard")).toBe("标准板");
    expect(gameOptionLabel("werewolf", "playerCount", 12)).toBe("12人 (标准)");
    expect(gameOptionLabel("undercover", "wordPack", "acg")).toBe("二次元");
    expect(gameOptionLabel("turtle_soup", "caseId", "rainy_key")).toBe("雨夜的钥匙");
    await i18n.changeLanguage("en");
    expect(gameOptionLabel("werewolf", "playerCount", 6)).toBe("6 players (Casual)");
  });

  it("未知 field/option 回退 fallback", () => {
    expect(gameFieldLabel("werewolf", "nope", "原始文案")).toBe("原始文案");
    expect(gameOptionLabel("werewolf", "template", "nope", "未知选项")).toBe("未知选项");
  });
});

describe("后端 raw 中文错误本地化兜底", () => {
  it("已知中文模式映射到本地化通用文案（任意语言一致）", async () => {
    expect(localizeErrorMessage("房间已满", "lobby.joinFailed")).toBe("房间已满");
    await i18n.changeLanguage("en");
    expect(localizeErrorMessage("房间已满", "lobby.joinFailed")).toBe("Room is full");
    expect(localizeErrorMessage("内容未通过安全检查", "errors.contentBlocked")).toBe("Content blocked by safety check");
  });

  it("流式请求失败映射到 aiChat.failed", async () => {
    await i18n.changeLanguage("zh-TW");
    expect(localizeErrorMessage("流式请求失败", "aiChat.failed")).toBe("AI 調用失敗");
  });

  it("zh-CN 透出未识别原始消息，其余语言用调用方 fallback key", async () => {
    const raw = "某种未知的后端错误";
    expect(localizeErrorMessage(raw, "wallet.redeemFailed")).toBe(raw);
    await i18n.changeLanguage("en");
    expect(localizeErrorMessage(raw, "wallet.redeemFailed")).toBe("Redemption failed");
  });
});


describe("稳定错误码优先于后端文案", () => {
  it.each(["zh-CN", "zh-TW", "en"])("%s 为所有业务码提供翻译并忽略冲突文案", async (language) => {
    await i18n.changeLanguage(language);
    const mappings = {
      PHASE_CHANGED: "phaseChanged", ALREADY_ACTED: "alreadyVoted", NOT_YOUR_TURN: "notYourTurn",
      INVALID_ACTION: "phaseNotSupported", ROOM_FULL: "roomFull", RATE_LIMITED: "rateLimited",
      RATE_LIMIT_UNAVAILABLE: "rateUnavailable", BUDGET_UNAVAILABLE: "budgetUnavailable",
      INSUFFICIENT_BALANCE: "insufficientBalance", BUDGET_RECONCILIATION_REQUIRED: "reconciliationRequired",
      ROOM_PASSWORD_INVALID: "roomPasswordInvalid", ROOM_PASSWORD_CHANGED: "roomPasswordChanged",
    };
    for (const [code, suffix] of Object.entries(mappings)) {
      const key = `errors.${suffix}`;
      expect(i18n.exists(key, { lng: language, fallbackLng: false })).toBe(true);
      expect(localizeErrorMessage("unrelated server wording", "lobby.joinFailed", code)).toBe(i18n.t(key));
    }
    expect(localizeErrorMessage("房间已满", "lobby.joinFailed", "UNKNOWN_CODE")).toBe(i18n.t("lobby.joinFailed"));
  });
});
