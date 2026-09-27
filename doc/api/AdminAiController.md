# AdminAiController 接口说明

基址：`/api/admin/ai`

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
