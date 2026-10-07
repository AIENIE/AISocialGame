
/**
 * 用户可见游戏展示文案（name/description/tags）的本地化映射：
 * 以稳定 game.id 为枚举，映射到 src/i18n/resources.ts 的 t key。
 * UI 渲染游戏卡片/房间列表时优先取 GAME_DISPLAY_KEYS 的翻译，
 * 未知游戏 id 则回退到后端/本地原始数据（见 src/i18n/gameTexts.ts）。
 * 运行时游戏数据由后端提供。
 */
export const GAME_DISPLAY_KEYS: Record<string, { name: string; desc: string; tags: string[] }> = {
  werewolf: {
    name: "games.werewolf.name",
    desc: "games.werewolf.desc",
    tags: ["games.werewolf.tags.0", "games.werewolf.tags.1", "games.werewolf.tags.2"],
  },
  undercover: {
    name: "games.undercover.name",
    desc: "games.undercover.desc",
    tags: ["games.undercover.tags.0", "games.undercover.tags.1", "games.undercover.tags.2"],
  },
  turtle_soup: {
    name: "games.turtle_soup.name",
    desc: "games.turtle_soup.desc",
    tags: ["games.turtle_soup.tags.0", "games.turtle_soup.tags.1", "games.turtle_soup.tags.2"],
  },
};

/**
 * configSchema 字段/选项展示文案的本地化映射（CreateRoom 板子配置区域）：
 * 以稳定 game.id + field.id → t key；选项以 option.value 字符串化后映射。
 * 未知 id 回退到 后端返回的 label（见 src/i18n/gameTexts.ts）。
 */
type ConfigFieldKeys = Record<string, { label: string; options?: Record<string, string> }>;

export const GAME_CONFIG_KEYS: Record<string, ConfigFieldKeys> = {
  werewolf: {
    template: {
      label: "create.field.template",
      options: {
        standard: "create.option.template.standard",
        guard: "create.option.template.guard",
        no_god: "create.option.template.no_god",
      },
    },
    playerCount: {
      label: "create.field.playerCount",
      options: {
        "1": "create.option.playerCount.1",
        "2": "create.option.playerCount.2",
        "3": "create.option.playerCount.3",
        "4": "create.option.playerCount.4",
        "5": "create.option.playerCount.5",
        "6": "create.option.playerCount.6",
        "7": "create.option.playerCount.7",
        "8": "create.option.playerCount.8",
        "9": "create.option.playerCount.9",
        "10": "create.option.playerCount.10",
        "12": "create.option.playerCount.12",
      },
    },
    witchRule: {
      label: "create.field.witchRule",
      options: {
        no_save: "create.option.witchRule.no_save",
        first_night: "create.option.witchRule.first_night",
        always_save: "create.option.witchRule.always_save",
      },
    },
    winCondition: {
      label: "create.field.winCondition",
      options: {
        side: "create.option.winCondition.side",
        city: "create.option.winCondition.city",
      },
    },
    speechTime: {
      label: "create.field.speechTime",
      options: {
        "60": "create.option.speakTime.60",
        "90": "create.option.speakTime.90",
        "120": "create.option.speakTime.120",
      },
    },
    hasLastWords: {
      label: "create.field.hasLastWords",
      options: {
        first_night: "create.option.hasLastWords.first_night",
        always: "create.option.hasLastWords.always",
        none: "create.option.hasLastWords.none",
      },
    },
  },
  undercover: {
    playerCount: {
      label: "create.field.playerCount",
      options: {
        "4": "create.option.playerCount.4",
        "5": "create.option.playerCount.5",
        "6": "create.option.playerCount.6",
        "7": "create.option.playerCount.7",
        "8": "create.option.playerCount.8",
        "9": "create.option.playerCount.9",
        "10": "create.option.playerCount.10",
      },
    },
    spyMode: {
      label: "create.field.spyMode",
      options: {
        auto: "create.option.spyMode.auto",
        manual: "create.option.spyMode.manual",
      },
    },
    hasBlank: { label: "create.field.hasBlank" },
    wordPack: {
      label: "create.field.wordPack",
      options: {
        daily: "create.option.wordPack.daily",
        idiom: "create.option.wordPack.idiom",
        acg: "create.option.wordPack.acg",
        tech: "create.option.wordPack.tech",
        custom: "create.option.wordPack.custom",
      },
    },
    speakTime: {
      label: "create.field.speakTime",
      options: {
        "30": "create.option.speakTime.30",
        "60": "create.option.speakTime.60",
        "90": "create.option.speakTime.90",
        "0": "create.option.speakTime.0",
      },
    },
  },
  turtle_soup: {
    playerCount: {
      label: "create.field.playerCount",
      options: {
        "1": "create.option.playerCount.1",
        "2": "create.option.playerCount.2",
        "3": "create.option.playerCount.3",
        "4": "create.option.playerCount.4",
        "6": "create.option.playerCount.6",
      },
    },
    caseId: {
      label: "create.field.caseId",
      options: {
        midnight_train: "create.option.caseId.midnight_train",
        rainy_key: "create.option.caseId.rainy_key",
      },
    },
    maxQuestions: { label: "create.field.maxQuestions" },
    aiAssist: { label: "create.field.aiAssist" },
  },
};
