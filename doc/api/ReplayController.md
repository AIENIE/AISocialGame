# ReplayController API

基础路径：`/api/replays`

## GET /api/replays

分页查询服务端回放归档。

### Query

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| gameId | String | 否 | 按玩法过滤，例如 `undercover`、`werewolf` |
| page | Integer | 否 | 页码，默认 `0` |
| size | Integer | 否 | 每页数量，默认 `20`，服务端最多 `100` |

### Response

返回 `PagedResponse<ReplayArchiveView>`。

## GET /api/replays/my

当前阶段与 `/api/replays` 一致，返回服务端归档列表。后续接入正式用户参与关系后会收敛为“我参与的对局”。

## GET /api/replays/{archiveId}

查询单个回放归档摘要。

### Response 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| id | String | 单局归档 ID |
| roomId | String | 房间 ID |
| gameId | String | 玩法 ID |
| roomName | String | 房间名 |
| winner | String | 获胜方 |
| playerCount | Integer | 玩家数 |
| totalRounds | Integer | 总轮次 |
| durationSeconds | Long | 对局时长 |
| eventCount | Long | 结构化事件数 |
| aiQualitySummary | Object | AI trace 数、fallback 数等安全摘要 |

## GET /api/replays/{archiveId}/events

按视角查询结构化事件流。

### Query

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| viewMode | `PUBLIC`/`PLAYER`/`GOD` | 否 | 默认 `PUBLIC` |
| viewerPlayerId | String | 否 | `PLAYER` 视角下用于读取自己的私有事件 |

### 可见性规则

- `PUBLIC`：只返回公开事件。
- `PLAYER`：返回公开事件，以及 `visibleToPlayerIds` 包含当前玩家的私有事件。
- `GOD`：返回本局全部事件，用于管理端质检和自动化验收。

### 安全约束

普通玩家视角不得展示隐藏身份、隐藏词语、夜晚目标、完整 Prompt 或内部推理链。AI 事件只暴露 trace id、fallback、质量 flags、模型和简短安全摘要。
