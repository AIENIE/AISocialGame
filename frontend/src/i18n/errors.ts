import i18n from "./config";

/** Stable business codes take precedence; message matching supports older servers only. */
const RAW_PATTERN_KEYS: Array<[RegExp, string]> = [
  [/房间已满|人满/, "errors.roomFull"],
  [/未通过安全|安全检查|违规/, "errors.contentBlocked"],
  [/请先登录|未登录/, "errors.loginRequired"],
  [/不存在|未找到/, "errors.notFound"],
  [/已出局/, "errors.eliminated"],
  [/已完成投票|已投过票/, "errors.alreadyVoted"],
  [/不需要你发言|未轮到你|还没轮到你/, "errors.notYourTurn"],
  [/当前阶段不支持|阶段不支持|该阶段不允许/, "errors.phaseNotSupported"],
  [/余额不足|积分不足/, "errors.insufficientBalance"],
  [/无权|权限不足|禁止访问/, "errors.forbidden"],
  [/已结束|已结算/, "errors.gameEnded"],
  [/流式请求失败|请求失败/, "aiChat.failed"],
];

const CODE_KEYS: Record<string, string> = {
  ROOM_EXPIRED: "rooms.expired", ROOM_CODE_INVALID: "rooms.invalidCode",
  PHASE_CHANGED: "errors.phaseChanged", ALREADY_ACTED: "errors.alreadyVoted", NOT_YOUR_TURN: "errors.notYourTurn",
  INVALID_ACTION: "errors.phaseNotSupported", ROOM_FULL: "errors.roomFull", RATE_LIMITED: "errors.rateLimited",
  RATE_LIMIT_UNAVAILABLE: "errors.rateUnavailable", BUDGET_UNAVAILABLE: "errors.budgetUnavailable",
  INSUFFICIENT_BALANCE: "errors.insufficientBalance", BUDGET_RECONCILIATION_REQUIRED: "errors.reconciliationRequired",
  ROOM_PASSWORD_INVALID: "errors.roomPasswordInvalid", ROOM_PASSWORD_CHANGED: "errors.roomPasswordChanged",
};

export function localizeErrorMessage(raw: string | undefined, fallbackKey: string, code?: string): string {
  if (code) return i18n.t(CODE_KEYS[code] || fallbackKey);
  if (raw) {
    for (const [pattern, key] of RAW_PATTERN_KEYS) {
      if (pattern.test(raw)) {
        return i18n.t(key);
      }
    }
  }
  if (!raw || i18n.language === "zh-CN") {
    // zh-CN 下原始后端消息即为用户语言，直接透出
    return raw || i18n.t(fallbackKey);
  }
  return i18n.t(fallbackKey);
}
