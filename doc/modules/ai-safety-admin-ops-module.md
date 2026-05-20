# AI 安全治理与 Admin 应急运营模块

## 模块作用

M4 为玩家内容、通用 AI Chat、管理端 AI 测试和 AI 玩家自动发言提供统一安全治理层。第一版采用本地规则和项目上下文校验，不直接依赖外部 moderation 服务，但保留 provider 化扩展边界，后续可接入 OpenAI Moderation 或内部 ai-service moderation RPC。

模块目标是先建立工程闭环：风险内容进入公开日志、事件流、WebSocket 或社区前先过 safety gate；高风险事件自动拦截或替换并写入安全队列；管理员在 `/admin/safety` 查看、确认、关闭事件，并下发临时控制。

## 代码架构

| 层级 | 主要类/页面 | 作用 |
|---|---|---|
| 数据模型 | `AiSafetyEvent`、`AiSafetyControl` | 保存安全事件和临时控制。 |
| 统一服务 | `AiSafetyService` | 执行本地规则、控制命中、事件创建、状态流转和摘要统计。 |
| 接入入口 | `RoomChatController`、`CommunityService`、`AiProxyService`、`GameRuntimeSupport` | 覆盖房间聊天、社区、AI Chat、Admin AI 测试、玩家/AI 局内发言。 |
| Admin API | `AdminSafetyController` | 提供 summary、事件查询、详情、确认、关闭、控制创建和停用。 |
| Admin 前端 | `frontend/src/pages/admin/SafetyAdmin.tsx` | 展示安全摘要、事件列表、事件详情和临时控制。 |

## 治理动作

- `ALLOW`：内容通过，不记录安全事件。
- `REDACT`：内容替换为安全提示，记录事件。
- `BLOCK`：拒绝本次输入或替换 AI 输出，记录事件。
- `RATE_LIMIT`：返回频率/长度限制提示，记录事件。
- `ESCALATE`：拦截并进入高危队列，等待管理员处理。

## 覆盖入口

- 房间 WebSocket 聊天：文字消息广播前审核，违规时通过 private event 给发送者安全提示。
- 社区发帖：入库前审核，`BLOCK` / `ESCALATE` 返回安全错误，`REDACT` 保存替换内容；访客名通过 URL 编码传输，后端解码后再写入作者名与安全上下文，避免中文昵称触发浏览器 header 约束。
- 通用 AI Chat：输入审核失败时不调用 AI；输出审核失败时不返回原文。
- 管理端 AI 测试：复用 AI Chat 安全链路，并标记 `ADMIN_AI_TEST` 来源。
- 局内发言：真人 `SPEAK` 和 AI 自动发言在写入 `GameState.logs`、`game_events` 前审核。

## 数据表

- `ai_safety_events`：记录来源、动作、级别、分类、状态、房间、用户、Persona、模型、trace、内容摘要、替换内容、原因和处理信息。
- `ai_safety_controls`：记录 `USER`、`ROOM`、`PERSONA`、`MODEL`、`GLOBAL` 维度的临时控制。

安全事件只保存摘要和替换内容，不保存完整 Prompt、隐藏身份、隐藏词、汤底或内部推理链。

## 验证方式

```bash
cd backend
mvn test -Dtest=AiSafetyServiceTest,AiProxyServiceTest
```

```bash
cd frontend
pnpm test:e2e tests/m4-safety.spec.ts
```
