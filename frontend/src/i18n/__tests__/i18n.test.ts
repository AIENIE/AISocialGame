// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import i18n, {
  STORAGE_KEY,
  getInitialLocale,
  isLocale,
  setLocale,
  type Locale,
} from "@/i18n/config";
import { resources } from "@/i18n/resources";

const LOCALES: Locale[] = ["zh-CN", "zh-TW", "en"];

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(async () => {
  window.localStorage.clear();
  await i18n.changeLanguage("zh-CN");
});

describe("资源完整性", () => {
  it("三种语言包含完全相同的 key 集合", () => {
    const keySets = LOCALES.map((locale) =>
      new Set(Object.keys(resources[locale].translation)),
    );
    for (const keys of keySets) {
      expect(keys).toEqual(keySets[0]);
    }
  });

  it("所有 key 的三语文案均非空", () => {
    for (const locale of LOCALES) {
      for (const [key, value] of Object.entries(resources[locale].translation)) {
        expect(value.trim(), `${locale}/${key}`).not.toBe("");
      }
    }
  });

  it("关键静态文案存在且三语不同", () => {
    const key = "nav.home";
    const values = LOCALES.map((l) => resources[l].translation[key]);
    expect(new Set(values).size).toBe(LOCALES.length);
  });
});

describe("Locale 类型与持久化", () => {
  it("isLocale 只接受三种受支持语言", () => {
    expect(isLocale("zh-CN")).toBe(true);
    expect(isLocale("zh-TW")).toBe(true);
    expect(isLocale("en")).toBe(true);
    expect(isLocale("fr-FR")).toBe(false);
    expect(isLocale("zh")).toBe(false);
    expect(isLocale(null)).toBe(false);
  });

  it("无存储记录时默认回退 zh-CN", () => {
    expect(getInitialLocale()).toBe("zh-CN");
  });

  it("非法存储值回退 zh-CN", () => {
    window.localStorage.setItem(STORAGE_KEY, "de-DE");
    expect(getInitialLocale()).toBe("zh-CN");
  });

  it("setLocale 持久化到安全键并同步 html.lang / document.title", async () => {
    setLocale("en");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("en");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe(resources.en.translation["app.title"]);
    // 等待 changeLanguage 完成，确认 i18n 内部语言一致
    await i18n.changeLanguage("en");
    expect(i18n.language).toBe("en");
  });
});

describe("翻译与回退", () => {
  it("默认语言为 zh-CN", () => {
    expect(i18n.language).toBe("zh-CN");
    expect(i18n.t("nav.home")).toBe(resources["zh-CN"].translation["nav.home"]);
  });

  it("未知语言回退到 zh-CN", () => {
    expect(i18n.t("nav.home", { lng: "fr-FR" })).toBe(
      resources["zh-CN"].translation["nav.home"],
    );
  });

  it("插值翻译可用", () => {
    expect(i18n.t("wallet.exchangeSuccess", { count: 3, lng: "zh-CN" })).toBe("兑换成功，到账 3 专属积分");
    expect(i18n.t("wallet.publicTokens", { before: 9, after: 6, lng: "en" })).toBe("Tokens: 9 → 6");
  });
});
