# M4 AI 安全治理与 Admin 应急运营开发记录

> 更新时间：2026-05-20
> 状态：已完成第一版工程闭环。

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

## 本次实现范围

- 玩家内容入口：
  - 房间 WebSocket 聊天在广播前审核，违规时通过 private event 给发送者安全提示。
  - 社区发帖在入库前审核，违规时拒绝或替换；访客名通过 URL 编码传输，后端再解码，避免中文昵称在浏览器 XHR header 中报错。
  - 局内真人 `SPEAK` 在写入公开日志和事件流前审核。
  - 被拦截或替换时，玩家收到清晰但不过度暴露规则细节的提示。
- AI 调用入口：
  - `/api/ai/chat` 与当前伪流式 `/api/ai/chat/stream` 对输入和输出都做安全审核。
  - 管理端 `/api/admin/ai/test-chat` 复用安全链路并标记 `ADMIN_AI_TEST` 来源。
  - AI 玩家自动发言在写入游戏日志、事件流和 WebSocket 前经过审核，不公开风险原文。
- 游戏上下文安全：
  - 复用 M1/M2/M3 的可见信息边界，避免模型输出隐藏身份、隐藏词、夜晚目标或未来海龟汤汤底。
  - Prompt Injection 检测优先识别“忽略规则”“输出系统提示”“泄露隐藏信息”等模式。
- 成本与异常治理：
  - 第一版支持通过本地规则和测试标记触发 `RATE_LIMIT` / `ESCALATE`。
  - 仪表盘预留成本异常计数，后续可接入更细的 token 时间窗口统计。

## 代码改动

- 后端新增 `AiSafetyEvent`、`AiSafetyControl`、`AiSafetyService` 和 `/api/admin/safety/*`。
- `backend/sql/schema.sql` 新增 `ai_safety_events` 与 `ai_safety_controls`。
- `RoomChatController`、`CommunityService`、`AiProxyService`、`GameRuntimeSupport` 接入统一 safety gate。
- Admin dashboard summary 增加未处理高危事件、24h 拦截数、成本异常、活跃控制数。
- 前端新增 `/admin/safety` 页面，管理端导航新增“安全运营”。
- 玩家端社区、AI Chat 和房间 private safety notice 使用统一可理解提示，不展示内部规则细节。

## 文档改动

- 新增模块文档：`doc/modules/ai-safety-admin-ops-module.md`。
- 新增 API 文档：`doc/api/AdminSafetyController.md`。
- 更新 `doc/README.md`、`doc/modules/README.md`、`doc/test/integratedTest.md`。

## 测试计划

- 后端自动化：
  - 审核规则命中、风险分类、严重级别、分级处置结果。
  - 控制查询与命中优先级。
  - 安全事件创建、确认、关闭和审计字段。
  - AI Chat 输入安全阻断时不调用 `AiGrpcClient`。
- 前端构建与页面验收：
  - 管理端 `/admin/safety` 能展示风险摘要、事件列表、筛选、详情、确认、关闭和控制操作。
  - 仪表盘安全指标正常展示。
  - 玩家侧安全提示不泄露内部审核规则、Prompt 或敏感上下文。
- Playwright 可复用验收：
  - `frontend/tests/m4-safety.spec.ts` 覆盖管理端安全页面、dashboard 安全指标和社区安全错误提示。
- browser-use 手工验收：
  - 本地服务启动后，在真实页面模拟房间危险聊天、社区风险发帖和管理员介入。

## 参考资料

- [OpenAI Safety best practices](https://platform.openai.com/docs/guides/safety-best-practices/constrain-user-input-and-limit-output-tokens.pls)
- [OpenAI Moderation](https://platform.openai.com/docs/guides/moderation/overview?lang=curl)
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/)
- [NIST AI Risk Management Framework](https://www.nist.gov/itl/ai-risk-management-framework)
- [生成式人工智能服务管理暂行办法](https://www.cac.gov.cn/2023-07/13/c_1690898326795531.htm)
- [人工智能生成合成内容标识办法](https://www.cac.gov.cn/2025-03/14/c_1743654685899683.htm)

## 本次变更记录

- 2026-05-19：仅新增 M4 规划文档，并在总路线图中插入 M4。
- 2026-05-20：完成 M4 第一版代码、文档和自动化测试接入。
- 2026-05-20：修复社区访客名在浏览器请求头中的中文编码问题，补充后端解码单测与 M4 回归测试。

## 后续遗留

- 外部 moderation provider 尚未接入，当前为本地规则和项目上下文校验。
- 成本异常统计当前有指标位，后续需要按用户/房间/模型/token 时间窗口细化。
- 如果未来 `/api/ai/chat/stream` 改成上游真实 token 流，需要重新设计增量输出审核策略。


## 2026-09-22 原始交付项收尾

状态：本轮代码与离线门禁通过，最终运行及语义验收未完成；历史记录保留。

- 新增项目 ai_call_usage，数据库锁+窗口聚合原子准入；Chat（含主持、昵称、通用及管理端）、Embeddings、OCR 均经同一入口。每次 attempt 唯一，实际用量缺失保留 NULL，原持久预算仍独立生效。
- 60秒用户/房间/模型/项目请求阈值20/30/120/180；5分钟实际token观察阈值10万/20万/100万/150万，按作用域去重升级，不推断金额。
- 暂停冻结倒计时并恢复剩余时间；人工观察停AI和托管但允许真人；公开禁言与私密夜间行动分开；调用前和提交前复核控制，迟到/被阻止结果不发布。
- 拒绝事件独立事务保存，成功动作/记忆/trace业务事务不变；本地危险、骚扰、色情、隐私、注入及正常反例回归。生产逻辑不再依赖 m4_test_* 内容。
- Admin三语控制动作、到期/操作人、确认/关闭/撤销流程更新。H2并发、重启、回滚及迁移重跑通过；真实MySQL完整迁移和运行控制尚待执行，关键词规则不声称全面语义识别。

统一门禁：标准前后端 L2 后端 511 项（506 通过、5 项外部跳过），前端 58 项，零失败/错误；类型检查、构建、独立 Java 指标及 Python 6 项指标测试通过。实际 MySQL 完整迁移仍未验证；本轮未启用 caller、调用真实模型、应用共享迁移、提交、推送或部署。

详细实现、逐项测试、语义局限与仓库外证据见 [收尾报告](../../test/m1-m5-closure-20260922.md)，后续操作按 [执行清单](../../operations/m1-m5-live-execution-checklist.md)。总体 L4 未通过。

## 2026-09-22 真实 MySQL 隔离门禁完成

本机原生 MySQL 8.0.45 验证三条真实 SQL 路径和两次重跑，329 个列定义、118 个索引列定义一致；旧数据、原预算和缺失用量保留。生产准入实现通过用户/房间/模型/项目四范围并发限制、完整应用上下文重建、窗口推进、重复完成、事务回滚后审计保留及拒绝前无 RPC 检查。18 项游戏事务回归也在 MySQL 上通过。

临时实例只监听回环地址，成功及故意失败路径均停止进程并清理凭据/数据。没有共享迁移或真实调用；本机 Windows 验证不代替共享 Linux 配置、备份恢复与最终运行验收。证据见[环境门禁报告](../../test/environment-gates-20260922.md)，L4 仍未通过。

## 2026-09-22 共享环境只读准备

受管启动、账号准备、迁移与指标入口共用矩阵目标校验；目标不一致、构建/批次未确认时预检不连接数据库。新增固定 SELECT 白名单元数据/预算采集，拒绝任意 SQL；读取缺失值保留未知。申请草案明确未授权，过期授权和草案由执行入口拒绝。

当前矩阵 13306 与原验收配置 23306 冲突，未执行共享 SQL；原 caller 未被启用，未登录或调整预算。后端 537 项 L2、前端 58 项与离线脚本/指标通过；治理最终运行验收仍未完成，L4 未通过。详见[只读预检报告](../../test/readonly-preflight-20260922.md)。
