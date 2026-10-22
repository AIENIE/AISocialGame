const labels: Record<string, readonly [string, string]> = {
  "费用预算":["費用預算","Spending budget"],
  "剩余预算":["剩餘預算","Remaining budget"],
  "实验因预算限制暂停":["實驗因預算限制暫停","Experiment paused by a budget limit"],
  "管理员已结束实验":["管理員已結束實驗","Experiment ended by administrator"],
  "对局、修正、复盘与评测按累计实扣共用预算。达到预算后暂停新调用；在途费用可能使最终实扣超过预算。按实际用量扣款，未知费用显示为待确认。":["對局、修正、復盤與評測按累計實扣共用預算。達到預算後暫停新呼叫；在途費用可能使最終實扣超過預算。按實際用量扣款，未知費用顯示為待確認。","Games, corrections, reviews and evaluations share the budget by actual debit. New calls pause at the budget; in-flight charges may exceed it. Charges use actual usage; unknown charges await confirmation."],
  "正常结束":["正常結束","Finished normally"],
  "复盘成功":["復盤成功","Reviews completed"],
  "中断":["中斷","Aborted"],
  "旧版禁词范围错误，动作被拒绝":["舊版禁詞範圍錯誤，動作被拒絕","Action rejected by the former overly broad word rule"],
  "命中本人禁词，等待修正":["命中本人禁詞，等待修正","Own forbidden word detected; correction required"],
  "三次禁词违规，角色出局":["三次禁詞違規，角色出局","Eliminated after three forbidden-word violations"],
  "动作提交失败":["動作提交失敗","Action submission failed"],
  "动作提交发生异常":["動作提交發生異常","Unexpected action submission failure"],
  "执行中断，具体原因需查看诊断":["執行中斷，具體原因需查看診斷","Execution aborted; inspect diagnostics for the cause"],
  "执行发生异常":["執行發生異常","Execution failed"],
  "调用结果待对账":["呼叫結果待對帳","Call outcome requires reconciliation"],
  "系统 AI 账户积分不足":["系統 AI 帳戶積分不足","System AI account has insufficient credits"],
  "所选模型已不可用":["所選模型已不可用","Selected model is unavailable"],
  "模型配置需要调整":["模型設定需要調整","Model configuration requires attention"],
  "模型调用失败":["模型呼叫失敗","Model call failed"],
  "模型调用超时":["模型呼叫逾時","Model call timed out"],
  "教练复盘失败":["教練復盤失敗","Coach review failed"],
  "AI 实验室": [
    "AI 實驗室",
    "AI Laboratory"
  ],
  "让每一局，成为下一局的经验。": [
    "讓每一局，成為下一局的經驗。",
    "Every game informs the next."
  ],
  "观察 AI 的连续对局，参与实验，见证策略如何变化。": [
    "觀察 AI 的連續對局，參與實驗，見證策略如何變化。",
    "Watch continuous AI matches, join an experiment, and follow evolving strategies."
  ],
  "谁是卧底": [
    "誰是臥底",
    "Undercover"
  ],
  "实验费用": [
    "實驗費用",
    "Experiment budget"
  ],
  "已扣积分折合": [
    "已扣積分折合",
    "Wallet charges"
  ],
  "未结预留": [
    "未結預留",
    "Outstanding holds"
  ],
  "供应商费用": [
    "供應商費用",
    "Provider cost"
  ],
  "笔调用": [
    "筆呼叫",
    "calls"
  ],
  "笔待确认": [
    "筆待確認",
    "awaiting confirmation"
  ],
  "对局尝试": [
    "對局嘗試",
    "Games attempted"
  ],
  "已处理至": [
    "已處理至",
    "Processed through"
  ],
  "席": [
    "席",
    "seats"
  ],
  "逐局学习": [
    "逐局學習",
    "Learn game by game"
  ],
  "局": [
    "局",
    "games"
  ],
  "还没有实验": [
    "還沒有實驗",
    "No experiments yet"
  ],
  "正在进行": [
    "正在進行",
    "Live tables"
  ],
  "对局": [
    "對局",
    "Game"
  ],
  "进入对局": [
    "進入對局",
    "Enter game"
  ],
  "观战": [
    "觀戰",
    "Watch"
  ],
  "当前没有运行中的对局": [
    "目前沒有執行中的對局",
    "No active games"
  ],
  "状态": [
    "狀態",
    "Status"
  ],
  "样本": [
    "樣本",
    "Sample"
  ],
  "获胜 AI": [
    "獲勝 AI",
    "Winning agents"
  ],
  "回放": [
    "重播",
    "Replay"
  ],
  "训练": [
    "訓練",
    "Training"
  ],
  "查看": [
    "查看",
    "View"
  ],
  "对局记录将在运行后出现": [
    "對局紀錄將在執行後出現",
    "Games appear here once the experiment starts."
  ],
  "对照评测": [
    "對照評測",
    "Controlled evaluation"
  ],
  "固定对手，成对复用局面；仅完整的纯 AI 对照样本计入结果。差异不保证代表能力提升。": [
    "固定對手，成對重用局面；僅完整的純 AI 對照樣本計入結果。差異不保證代表能力提升。",
    "Opponents and paired starting conditions stay fixed. Only complete AI-only pairs count. Differences do not guarantee improvement."
  ],
  "尚未运行对照评测": [
    "尚未執行對照評測",
    "No evaluations yet"
  ],
  "组成对样本": [
    "組成對樣本",
    "paired samples"
  ],
  "指标": [
    "指標",
    "Metric"
  ],
  "初始经验": [
    "初始經驗",
    "Initial experience"
  ],
  "训练后经验": [
    "訓練後經驗",
    "Learned experience"
  ],
  "胜率": [
    "勝率",
    "Win rate"
  ],
  "有效行动率": [
    "有效行動率",
    "Valid action rate"
  ],
  "已结束尝试 / 失败": [
    "已結束嘗試 / 失敗",
    "Finished attempts / failed"
  ],
  "胜率仅含完整配对；有效行动率包含中断局的已执行行动，失败率按已结束尝试计算。": [
    "勝率僅含完整配對；有效行動率包含中斷局的已執行行動，失敗率按已結束嘗試計算。",
    "Win rates use complete pairs only. Action validity includes executed actions from interrupted games; failure rates include all finished attempts."
  ],
  "失败率": [
    "失敗率",
    "Failure rate"
  ],
  "胜率差异": [
    "勝率差異",
    "Win rate difference"
  ],
  "上一页": [
    "上一頁",
    "Previous"
  ],
  "下一页": [
    "下一頁",
    "Next"
  ],
  "返回实验室": [
    "返回實驗室",
    "Back to laboratory"
  ],
  "参战下一局": [
    "參戰下一局",
    "Join the next game"
  ],
  "先报名，在下一局开场前确认席位；空位由 AI 自动补齐。": [
    "先報名，在下一局開場前確認席位；空位由 AI 自動補齊。",
    "Enrol now and confirm your seat before the next game. AI fills unconfirmed seats."
  ],
  "报名下一局": [
    "報名下一局",
    "Enrol for next game"
  ],
  "等待下一局确认窗口": [
    "等待下一局確認視窗",
    "Waiting for a seat offer"
  ],
  "请在倒计时结束前确认": [
    "請在倒數結束前確認",
    "Confirm before the countdown ends"
  ],
  "确认参战": [
    "確認參戰",
    "Confirm seat"
  ],
  "席位已确认，等待开局": [
    "席位已確認，等待開局",
    "Seat confirmed; waiting for the game"
  ],
  "正在参战，本局结束后可再次报名": [
    "正在參戰，本局結束後可再次報名",
    "Your game is running. Enrol again after it finishes."
  ],
  "取消报名": [
    "取消報名",
    "Withdraw"
  ],
  "对局历史": [
    "對局歷史",
    "Match history"
  ],
  "操作失败，请重试": [
    "操作失敗，請重試",
    "Action failed. Try again."
  ],
  "报名状态已更新": [
    "報名狀態已更新",
    "Enrolment updated"
  ],
  "管理员管理实验；真人参与使用现有游戏规则。": [
    "管理員管理實驗；真人參與使用現有遊戲規則。",
    "Experiments are managed by administrators. Human participants use the existing game rules."
  ],
  "DRAFT": [
    "草稿",
    "Draft"
  ],
  "RUNNING": [
    "執行中",
    "Running"
  ],
  "PAUSING": [
    "本局後暫停",
    "Pausing after current games"
  ],
  "PAUSED": [
    "已暫停",
    "Paused"
  ],
  "STOPPING": [
    "本局後停止",
    "Stopping after current games"
  ],
  "STOPPED": [
    "已停止",
    "Stopped"
  ],
  "COMPLETED": [
    "已完成",
    "Completed"
  ],
  "WAITING": [
    "等待開局",
    "Waiting"
  ],
  "PLAYING": [
    "對局中",
    "Playing"
  ],
  "FINISHED": [
    "等待復盤",
    "Awaiting review"
  ],
  "REVIEWING": [
    "復盤中",
    "Reviewing"
  ],
  "REVIEWED": [
    "已處理",
    "Processed"
  ],
  "REVIEW_FAILED": [
    "復盤失敗",
    "Review failed"
  ],
  "ABORTED": [
    "已中斷",
    "Interrupted"
  ],
  "BASELINE": [
    "初始經驗",
    "Baseline"
  ],
  "CANDIDATE": [
    "訓練後經驗",
    "Candidate"
  ],
  "CIVILIAN": [
    "平民",
    "Civilian"
  ],
  "UNDERCOVER": [
    "臥底",
    "Undercover"
  ],
  "BLANK": [
    "白板",
    "Blank"
  ]
};
const chineseStatuses: Record<string, string> = {
  "DRAFT": "草稿",
  "RUNNING": "运行中",
  "PAUSING": "本局后暂停",
  "PAUSED": "已暂停",
  "STOPPING": "本局后停止",
  "STOPPED": "已停止",
  "COMPLETED": "已完成",
  "WAITING": "等待开局",
  "PLAYING": "对局中",
  "FINISHED": "等待复盘",
  "REVIEWING": "复盘中",
  "REVIEWED": "已处理",
  "REVIEW_FAILED": "复盘失败",
  "ABORTED": "已中断",
  "BASELINE": "初始经验",
  "CANDIDATE": "训练后经验",
  "CIVILIAN": "平民",
  "UNDERCOVER": "卧底",
  "BLANK": "白板"
};
export function labText(locale: string, text: string): string {
  return locale.startsWith("en") ? labels[text]?.[1] ?? text : locale === "zh-TW" ? labels[text]?.[0] ?? text : chineseStatuses[text] ?? text;
}
