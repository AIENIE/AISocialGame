# AdminAiController 接口说明

基址：`/api/admin/ai`

## GET /models
- 用途：管理端查看 AI 模型列表。
- 请求头：`X-Admin-Token` (required)
- 响应 200：`AiModelView[]`

## POST /test-chat
- 用途：管理端发起 AI 测试调用。
- 请求头：`X-Admin-Token` (required)
- 请求体：
  - `userId` (long, optional)
  - `sessionId` (string, optional)
  - `model` (string, optional)
  - `messages` (array, required)
- 响应 200：`AiChatResponse`

## GET /decision-traces
- 用途：管理端分页查看 AI 决策质检记录。
- 请求头：`X-Admin-Token` (required)
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
- 请求头：`X-Admin-Token` (required)
- Query：
  - `personaId` (string, optional)
- 响应 200：`AdminAiPersonaMemoryView[]`

## POST /persona-memories/{id}/reset
- 用途：清空单条 Persona 记忆，保留记录行与作用域。
- 请求头：`X-Admin-Token` (required)
- 响应 204：无响应体
