import { Game } from "@/types";

export const GAMES: Game[] = [
  {
    id: "werewolf",
    name: "狼人杀",
    description: "经典的社交推理游戏。天黑请闭眼，与伪装者博弈，活到最后。",
    coverUrl: "Moon",
    tags: ["逻辑推理", "社交", "硬核"],
    minPlayers: 6,
    maxPlayers: 12,
    status: "active",
    onlineCount: 1240,
    configSchema: [
      // --- 基础板子配置 (Templates) ---
      {
        id: "template",
        label: "板子预设",
        type: "select", // In UI this will be rendered as cards
        defaultValue: "standard",
        options: [
          { label: "预女猎白 (标准)", value: "standard" },
          { label: "预女猎守 (进阶)", value: "guard" },
          { label: "生推局 (无神职)", value: "no_god" },
        ],
      },
      {
        id: "playerCount",
        label: "玩家人数",
        type: "select",
        defaultValue: 12,
        options: [
          { label: "6人 (娱乐)", value: 6 },
          { label: "9人 (进阶)", value: 9 },
          { label: "12人 (标准)", value: 12 },
        ],
      },
      // --- 高级规则 (Advanced) ---
      {
        id: "witchRule",
        label: "女巫规则",
        type: "select",
        defaultValue: "first_night",
        options: [
          { label: "全程不可自救", value: "no_save" },
          { label: "仅首夜可自救", value: "first_night" },
          { label: "全程可自救", value: "always_save" },
        ],
      },
      {
        id: "winCondition",
        label: "胜利条件",
        type: "select",
        defaultValue: "side",
        options: [
          { label: "屠边规则", value: "side" },
          { label: "屠城规则", value: "city" },
        ],
      },
      {
        id: "speechTime",
        label: "发言时长",
        type: "select",
        defaultValue: 120,
        options: [
          { label: "60秒", value: 60 },
          { label: "90秒", value: 90 },
          { label: "120秒", value: 120 },
        ],
      },
      {
        id: "hasLastWords",
        label: "遗言规则",
        type: "select",
        defaultValue: "first_night",
        options: [
          { label: "仅首夜", value: "first_night" },
          { label: "全程有遗言", value: "always" },
          { label: "无遗言", value: "none" },
        ],
      },
    ],
  },
  {
    id: "undercover",
    name: "谁是卧底",
    description: "用语言描述你的词语，找出隐藏在人群中的卧底！",
    coverUrl: "Spy",
    tags: ["聚会", "休闲", "语言类"],
    minPlayers: 4,
    maxPlayers: 10,
    status: "active",
    onlineCount: 856,
    configSchema: [
      {
        id: "playerCount",
        label: "玩家人数",
        type: "select",
        defaultValue: 6,
        options: [
          { label: "4人", value: 4 },
          { label: "5人", value: 5 },
          { label: "6人", value: 6 },
          { label: "7人", value: 7 },
          { label: "8人", value: 8 },
          { label: "9人", value: 9 },
          { label: "10人", value: 10 },
        ],
      },
      {
        id: "spyMode",
        label: "卧底数量模式",
        type: "select",
        defaultValue: "auto",
        options: [
          { label: "系统自动 (推荐)", value: "auto" },
          { label: "手动设置", value: "manual" },
        ],
      },
      {
        id: "hasBlank",
        label: "加入白板玩家",
        type: "boolean",
        defaultValue: false,
      },
      {
        id: "wordPack",
        label: "词库类型",
        type: "select",
        defaultValue: "daily",
        options: [
          { label: "日常生活", value: "daily" },
          { label: "成语俗语", value: "idiom" },
          { label: "二次元", value: "acg" },
          { label: "硬核科技", value: "tech" },
          { label: "自定义词库", value: "custom" },
        ],
      },
      {
        id: "speakTime",
        label: "发言时长",
        type: "select",
        defaultValue: 60,
        options: [
          { label: "30秒", value: 30 },
          { label: "60秒", value: 60 },
          { label: "90秒", value: 90 },
          { label: "不限时", value: 0 },
        ],
      },
    ],
  },
  {
    id: "turtle_soup",
    name: "海龟汤",
    description: "通过提问“是”或“否”来还原离奇故事的真相。",
    coverUrl: "BookOpen",
    tags: ["悬疑", "合作", "故事"],
    minPlayers: 1,
    maxPlayers: 6,
    status: "active",
    onlineCount: 0,
    configSchema: [
      {
        id: "playerCount",
        label: "玩家人数",
        type: "select",
        defaultValue: 2,
        options: [
          { label: "1人", value: 1 },
          { label: "2人", value: 2 },
          { label: "3人", value: 3 },
          { label: "4人", value: 4 },
          { label: "6人", value: 6 },
        ],
      },
      {
        id: "caseId",
        label: "题目",
        type: "select",
        defaultValue: "midnight_train",
        options: [
          { label: "末班车的乘客", value: "midnight_train" },
          { label: "雨夜的钥匙", value: "rainy_key" },
        ],
      },
      {
        id: "maxQuestions",
        label: "问题上限",
        type: "number",
        defaultValue: 12,
        min: 4,
        max: 30,
      },
      {
        id: "aiAssist",
        label: "AI 玩家追问",
        type: "boolean",
        defaultValue: true,
      },
    ],
  },
];
