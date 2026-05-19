# M4 AI 安全治理与 Admin 应急运营规划

> 更新时间：2026-05-19
> 状态：规划阶段，尚未进入代码开发。

## 阶段目标

M4 在 GameEngine 插件化之后、海龟汤玩法之前补齐安全治理和运营介入能力。目标不是一次性完成法律合规认证，而是先建立工程化闭环：降低玩家聊天、社区内容、通用 AI Chat、AI 玩家发言和管理端测试调用出现违规内容的概率，并让管理员在危险聊天、Prompt 注入、隐藏信息泄露、异常额度消耗或模型/Persona 行为异常时能及时发现、确认、介入和复盘。

本阶段继续坚持 AI 身份显式标识，不让 AI 冒充真人；玩家界面只展示安全后的公开信息，完整 Prompt、隐藏身份、隐藏词语、汤底和内部推理链不得暴露给普通玩家。

## 设计方向

- 新增可插拔 AI Safety 治理层：
  - 默认实现使用本地规则、项目上下文校验和现有 AI 质量 flags。
  - 预留外部 moderation provider 接口，后续可接入 OpenAI Moderation、ai-service moderation RPC 或其他合规服务。
  - 审核结果统一归一为 `ALLOW`、`REDACT`、`BLOCK`、`RATE_LIMIT`、`ESCALATE`。
- 新增安全事件与临时控制：
  - `ai_safety_events` 记录风险来源、用户、房间、Persona、模型、trace、分类、严重级别、处置动作、状态、内容摘要和时间。
  - `ai_safety_controls` 支持 `USER`、`ROOM`、`PERSONA`、`MODEL`、`GLOBAL` 维度控制。
  - 控制动作覆盖禁言、房间安全暂停、Persona 禁用、模型禁用、AI 调用限流和强制运营观察。
- 完善管理端可观测性：
  - 新增 `/admin/safety` 页面和 `/api/admin/safety/*` 接口。
  - 安全队列支持按状态、严重级别、来源、房间、用户、Persona、模型和时间筛选。
  - 仪表盘增加未处理高危事件、近 24 小时拦截数、成本异常、活跃控制数。

## 未来实现范围

- 玩家内容入口：
  - 房间 WebSocket 聊天在广播前审核。
  - 社区发帖在入库前审核。
  - 被拦截或替换时，玩家收到清晰但不过度暴露规则细节的提示。
- AI 调用入口：
  - `/api/ai/chat` 与 `/api/ai/chat/stream` 对输入和输出都做安全审核。
  - 管理端 `/api/admin/ai/test-chat` 记录安全摘要与额度消耗。
  - AI 玩家自动发言在写入游戏日志、事件流和 WebSocket 前经过审核。
- 游戏上下文安全：
  - 复用 M1/M2/M3 的可见信息边界，避免模型输出隐藏身份、隐藏词、夜晚目标或未来海龟汤汤底。
  - Prompt Injection 检测优先识别“忽略规则”“输出系统提示”“泄露隐藏信息”等模式。
- 成本与异常治理：
  - 按用户、房间、Persona、模型和时间窗口统计异常调用量与 token 消耗。
  - 异常时触发 `RATE_LIMIT` 或 `ESCALATE`，并支持管理员下发临时控制。

## 测试计划

- 后端单元测试：
  - 审核规则命中、风险分类、严重级别、分级处置结果。
  - 控制查询与命中优先级。
  - 安全事件创建、确认、关闭和审计字段。
  - AI 输出安全降级、Prompt Injection 检测、隐藏信息泄露检测、异常额度限流。
- 后端集成测试：
  - 房间聊天风险内容被拦截并生成安全事件。
  - 社区发帖风险内容被拒绝或替换。
  - AI 玩家发言触发风险时不会将原文写入公开日志、事件流或 WebSocket。
  - Persona、模型、用户、房间控制生效后，对应操作被限制并生成审计记录。
- 前端构建与页面验收：
  - 管理端 `/admin/safety` 能展示风险摘要、事件列表、筛选、详情、确认、关闭和控制操作。
  - 仪表盘安全指标正常展示。
  - 玩家侧安全提示不泄露内部审核规则、Prompt 或敏感上下文。
- Playwright 可复用验收：
  - 登录管理端，触发风险聊天，验证事件生成、确认/关闭和临时控制生效。
  - 验证原有 AI 对局、回放和 AI 质检入口不回退。
- browser-use 手工验收：
  - 在真实房间中模拟危险聊天、异常频率发送和管理员介入，确认端到端体验。
  - 手工查看管理端安全队列与事件详情，确认信息足够运营判断但不暴露内部推理链。

## 参考资料

- [OpenAI Safety best practices](https://platform.openai.com/docs/guides/safety-best-practices/constrain-user-input-and-limit-output-tokens.pls)
- [OpenAI Moderation](https://platform.openai.com/docs/guides/moderation/overview?lang=curl)
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/)
- [NIST AI Risk Management Framework](https://www.nist.gov/itl/ai-risk-management-framework)
- [生成式人工智能服务管理暂行办法](https://www.cac.gov.cn/2023-07/13/c_1690898326795531.htm)
- [人工智能生成合成内容标识办法](https://www.cac.gov.cn/2025-03/14/c_1743654685899683.htm)

## 本次变更记录

- 仅新增 M4 规划文档，并在总路线图中插入 M4。
- 原海龟汤、社交平台化和 AI 运营后台里程碑顺延。
- 本次不进行代码开发、建表、接口实现或测试执行。
