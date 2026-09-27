const texts: Record<string, [string, string]> = {
  "管理端完整回放": ["管理端完整回放", "Open admin replay"],
  "正在读取回放": ["正在讀取回放", "Loading replay"],
  "回放读取失败，请确认管理权限后重试。": ["回放讀取失敗，請確認管理權限後重試。", "Replay unavailable. Check admin access and retry."],
  "仅管理端可见的事件证据": ["僅管理端可見的事件證據", "Admin-only event evidence"],

  "回放范围": ["回放範圍", "Replay scope"],
  "我的回放": ["我的回放", "My replays"], "公开回放": ["公開回放", "Public replays"],
  "开始时间": ["開始時間", "From"], "结束时间": ["結束時間", "Until"],
  "参与者 ID": ["參與者 ID", "Participant ID"], "重试": ["重試", "Retry"],
  "上一页": ["上一頁", "Previous"], "下一页": ["下一頁", "Next"],
  "请登录后查看本人回放；无权读取其他玩家视角。": ["請登入後查看本人回放；無權讀取其他玩家視角。", "Sign in to view your replays. Other players’ private views are unavailable."],

  "AI 安全与应急运营": [
    "AI 安全與應急營運",
    "AI safety and operations"
  ],
  "监控风险内容、确认事件，并对用户、房间、Persona、模型或全局下发临时控制。": [
    "監控風險內容、確認事件，並對使用者、房間、角色、模型或全域下發臨時控制。",
    "Review risk events and manage temporary controls."
  ],
  "未处理高危": [
    "未處理高危",
    "Open high risks"
  ],
  "24h 拦截/替换": [
    "24h 攔截/替換",
    "Blocked/redacted in 24h"
  ],
  "成本异常": [
    "用量異常",
    "Usage anomalies"
  ],
  "活跃控制": [
    "作用中控制",
    "Active controls"
  ],
  "加载安全运营数据失败": [
    "載入安全營運資料失敗",
    "Failed to load safety data"
  ],
  "已确认安全事件": [
    "已確認安全事件",
    "Event acknowledged"
  ],
  "确认失败": [
    "確認失敗",
    "Acknowledgement failed"
  ],
  "已关闭安全事件": [
    "已關閉安全事件",
    "Event closed"
  ],
  "关闭失败": [
    "關閉失敗",
    "Closing failed"
  ],
  "请输入控制目标": [
    "請輸入控制目標",
    "Enter a control target"
  ],
  "临时控制已创建": [
    "臨時控制已建立",
    "Control created"
  ],
  "创建控制失败": [
    "建立控制失敗",
    "Failed to create control"
  ],
  "控制已停用": [
    "控制已停用",
    "Control disabled"
  ],
  "停用失败": [
    "停用失敗",
    "Failed to disable control"
  ],
  "无内容摘要": [
    "無內容摘要",
    "No content summary"
  ],
  "用户/房间/Persona/模型，GLOBAL 用 *": [
    "使用者/房間/角色/模型，GLOBAL 使用 *",
    "User/room/persona/model; use * for GLOBAL"
  ],
  "原因": [
    "原因",
    "Reason"
  ],
  "临时控制": [
    "臨時控制",
    "Temporary controls"
  ],
  "事件详情": [
    "事件詳情",
    "Event details"
  ],
  "停用": [
    "停用",
    "Disable"
  ],
  "全部": [
    "全部",
    "All"
  ],
  "创建控制": [
    "建立控制",
    "Create control"
  ],
  "房间": [
    "房間",
    "Room"
  ],
  "暂无安全事件。": [
    "暫無安全事件。",
    "No safety events."
  ],
  "暂无活跃控制。": [
    "暫無作用中控制。",
    "No active controls."
  ],
  "来源": [
    "來源",
    "Source"
  ],
  "查询": [
    "查詢",
    "Search"
  ],
  "模型": [
    "模型",
    "Model"
  ],
  "状态": [
    "狀態",
    "Status"
  ],
  "用户": [
    "使用者",
    "User"
  ],
  "选择左侧事件查看详情。": [
    "選擇左側事件查看詳情。",
    "Select an event to view details."
  ],
  "确认": [
    "確認",
    "Acknowledge"
  ],
  "关闭": [
    "關閉",
    "Close"
  ],
  "事件列表": [
    "事件列表",
    "Events"
  ],
  "禁言": [
    "禁言",
    "Mute"
  ],
  "暂停房间": [
    "暫停房間",
    "Pause room"
  ],
  "人工观察": [
    "人工觀察",
    "Human observation"
  ],
  "禁用 AI": [
    "停用 AI",
    "Disable AI"
  ],
  "到期时间": [
    "到期時間",
    "Expires at"
  ],
  "随机题目": [
    "隨機題目",
    "Random puzzle"
  ],
  "重新加载": [
    "重新載入",
    "Reload"
  ],
  "配置加载失败，暂不能创建房间": [
    "設定載入失敗，暫不能建立房間",
    "Configuration unavailable; room creation is disabled"
  ],
  "等待主持裁决，不消耗新的提问机会": [
    "等待主持裁決，不消耗新的提問機會",
    "Waiting for host; no additional question is charged"
  ]
};
export const closureText = (locale: string, text: string) => locale.startsWith("en") ? (texts[text]?.[1] || text) : locale === "zh-TW" ? (texts[text]?.[0] || text) : text;
