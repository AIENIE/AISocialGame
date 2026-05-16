# 结构化事件与服务端回放模块

## 模块作用

M2 将对局过程从 `GameState.logs` 的文本日志扩展为服务端结构化事件流。事件流用于回放播放、AI 质检定位、赛后战报和后续观战/审计能力。现阶段 `GameState.logs` 仍保留给房间页使用，`game_events` 与 `game_archives` 是回放与质检的新底座。

## 代码架构

| 层级 | 主要类 | 作用 |
|---|---|---|
| 事件模型 | `GameEvent`、`GameEventVisibility` | 保存单局事件的序号、阶段、轮次、行为人、目标、可见性和结构化数据。 |
| 归档模型 | `GameArchive` | 保存一局结束后的房间、胜负、玩家快照、事件数、AI 质量摘要和时间信息。 |
| 事件写入 | `GameEventRecorder` | 为当前 `GameState` 分配 `archiveId`，按单局递增 `seq` 追加 PUBLIC/PRIVATE/GOD 事件。 |
| 归档查询 | `ReplayArchiveService` | 结算时生成 archive，并按 PUBLIC/PLAYER/GOD 视角过滤事件。 |
| HTTP API | `ReplayController` | 提供回放列表、详情和事件流查询。 |

## 事件可见性

- `PUBLIC`：所有回放视角可见，例如开局、公开发言、已提交投票、阶段日志、结算。
- `PRIVATE`：仅指定玩家可见，例如自己的身份/词语、预言家查验结果、女巫操作结果。
- `GOD`：仅上帝视角/质检使用，例如完整身份、隐藏词、真实投票目标、夜晚目标。

玩家端回放默认使用 `PUBLIC` 或 `PLAYER`。前端和 API 不应在普通视角展示隐藏身份、隐藏词、夜晚目标、完整 Prompt 或内部推理链。

## 数据流

1. 开局时 `GameEventRecorder` 在 `GameState.data.archiveId` 写入本局归档 ID，并记录 `GAME_START`。
2. 身份/词语分配写入 `PRIVATE` 和 `GOD` 事件，公开视角不可见。
3. 玩家或 AI 发言、投票、夜晚行动时，`GamePlayService` 在保留文本日志的同时追加结构化事件。
4. AI 行为的公开事件只包含安全摘要；详细信念、记忆和原始输出仍由 `ai_decision_traces` 管理。
5. 结算时记录 `GAME_END`，并由 `ReplayArchiveService` 生成 `game_archives`。
6. 前端 `/replays` 与 `/replay/:archiveId` 优先读取服务端回放；服务端不可用时保留本地降级存档。

## 数据表

- `game_events`：结构化事件流，核心索引为 `archive_id + seq`。
- `game_archives`：单局归档摘要，包含玩家快照、胜负、轮次、事件数和 AI 质量摘要。

## 验证方式

```bash
cd backend
mvn test -Dtest=ReplayArchiveServiceTest,GamePlayServiceUndercoverTest,GamePlayServiceWerewolfTest
```

真实浏览器验收：

```bash
cd frontend
REAL_ACCEPTANCE=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:11030 pnpm test:acceptance
```
