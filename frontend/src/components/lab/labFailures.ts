export function failureText(code:string):string {
  return ({
    LEGACY_WORD_SCOPE_REJECTION:"旧版禁词范围错误，动作被拒绝",
    OWN_WORD_FORBIDDEN:"命中本人禁词，等待修正",
    SECRET_WORD_LEAK:"命中本人禁词，等待修正",
    FORBIDDEN_WORD_ELIMINATED:"三次禁词违规，角色出局",
    SUBMISSION_EXCEPTION:"动作提交失败",
    UNEXPECTED_SUBMISSION_FAILURE:"动作提交发生异常",
    EXECUTION_ABORT:"执行中断，具体原因需查看诊断",
    EXECUTION_EXCEPTION:"执行发生异常",
    LAB_BUDGET_EXHAUSTED:"实验剩余额度不足以预留调用",
    BUDGET_RECONCILIATION_REQUIRED:"调用结果待对账",
    AI_CREDIT_INSUFFICIENT:"系统 AI 账户积分不足",
    MODEL_SELECTION_UNAVAILABLE:"所选模型已不可用",
    INVALID_MODEL_CONFIG:"模型配置需要调整",
    MODEL_UNAVAILABLE:"模型调用失败",
    MODEL_TIMEOUT:"模型调用超时",
    LAB_REVIEW_FAILED:"教练复盘失败",
    MODEL_ACTION_APPLIED:"动作已提交",
    TURN_OBSOLETE:"行动机会已结束"
  } as Record<string,string>)[code]||code||"未记录具体原因";
}
