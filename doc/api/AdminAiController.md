# AdminAiController 接口说明

基址：`/api/admin/ai`

## 模型配置

管理页面为 `/admin/models`。以下接口沿用现有管理员会话、来源校验及操作二次确认；读取不会自动同步。

| 方法与路径 | 用途及返回 |
| --- | --- |
| `GET /model-config` | 返回 `catalogVersion`、`syncedAt`、`models`、`functions`。 |
| `POST /model-config/sync` | 使用当前配置的 ai-service 调用方执行 `ListCallerModels`，原子保存目录并返回新快照。 |
| `PUT /model-config/functions/{functionCode}` | 保存一项功能配置，返回更新后的功能及版本。 |

`functionCode` 为 `UNDERCOVER`、`WEREWOLF`、`TURTLE_SOUP_PLAYER`、`TURTLE_SOUP_HOST`。保存体包含 `modelKey`、`thinkingMode`（`DEFAULT` / `ENABLED` / `DISABLED`）、可为空的 `thinkingEffort`（`MINIMAL` / `LOW` / `MEDIUM` / `HIGH` / `XHIGH` / `MAX`）、读取时的 `catalogVersion` 与该功能的 `version`。

模型目录以 `modelKey` 为稳定标识，保留 `thinking.declared`、可开启/关闭能力、实际支持的强度及可选默认值。默认值缺失为未知；能力缺失或 `declared=false` 时仅允许 `DEFAULT`。跟随默认不传思考字段，关闭只传 `thinking_enabled=false`，开启传 `true` 并按选择附加强度；未选强度仍使用服务默认。

首次同步前沿用环境默认模型；首次同步尝试按 ID 或模型键导入四项配置，无法匹配时保留原值并标记 `INVALID`。同步失败保留原快照，成功空列表正常保存。模型下架或能力缩减不会自动换模型/强度，相关任务停止后续外发。四项可以独立保存，狼人杀和海龟汤仍受原预算限制。

保存参数失效返回 `400 / AI_MODEL_CONFIG_INVALID`；目录或配置版本冲突返回 `409 / AI_MODEL_CONFIG_CONFLICT`，前端保留编辑内容并提供重新读取。未同步返回 `409 / AI_MODEL_SYNC_REQUIRED`，迁移未初始化返回 `503 / AI_MODEL_CONFIG_NOT_INITIALIZED`，同步失败或旧服务未实现 RPC 返回 `502 / AI_MODEL_SYNC_FAILED`。

配置用于新的 AI 任务；已开始任务的 `diagnostics.modelSelection` 固定模型、思考设置和配置版本，修正及预算恢复沿用同一快照。目录能力失效会在后续发送前拒绝，已发生调用继续按原预算流程对账。结构增量为 `20261019_ai_model_config.sql`，登记于迁移序列 13；当前全量结构同步维护，冻结基线不变。

## GET /models
- 用途：管理端查看 AI 模型列表。
- 请求头：HttpOnly 管理员会话 cookie (required)
- 响应 200：`AiModelView[]`

## POST /test-chat
- 用途：管理端发起 AI 测试调用。
- 请求头：HttpOnly 管理员会话 cookie (required)
- 请求体：
  - `userId` (long, optional)
  - `sessionId` (string, optional)
  - `model` (string, optional)
  - `messages` (array, required)
- 响应 200：`AiChatResponse`

## GET /decision-traces
- 用途：管理端分页查看 AI 决策质检记录。
- 请求头：HttpOnly 管理员会话 cookie (required)
- Query：
  - `roomId` (string, optional)
  - `gameId` (string, optional)
  - `personaId` (string, optional)
  - `action` (string, optional)：`SPEECH` / `VOTE` / `NIGHT_ACTION`
  - `fallback` (boolean, optional)
  - `qualityFlag` (string, optional)
  - `page` (int, default `0`)
  - `size` (int, default `20`, max `100`)
- 响应 200：`PagedResponse<AdminAiDecisionTraceView>`

## GET /persona-memories
- 用途：查看跨局 Persona 记忆。
- 请求头：HttpOnly 管理员会话 cookie (required)
- Query：
  - `personaId` (string, optional)
- 响应 200：`AdminAiPersonaMemoryView[]`

## POST /persona-memories/{id}/reset
- 用途：清空单条 Persona 记忆，保留记录行与作用域。
- 请求头：HttpOnly 管理员会话 cookie (required)
- 响应 204：无响应体


## 2026-09-22 质检关联

新 v2 trace 的 quality.instanceId 对应 archiveId，quality.eventIds 对应本次产生的规则事件，保留 jobId 与提示词/预设/诊断版本。管理端质检可通过 [受保护回放入口](ReplayController.md) 定位完整事件，普通回放不能读取 GOD。旧 trace 缺少 instanceId 时保持未知，不凭roomId补造成单局关联。跨局经验候选只在既有 approve 流程确认后进入模型上下文。
