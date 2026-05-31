# GamePlayController 接口说明

## 简介
- 职责：管理狼人杀/谁是卧底的对局状态查询与动作提交。
- 鉴权要求：全部接口均需要 `X-Auth-Token`，玩家身份由登录用户 ID 决定。
- 基础路径：`/api/games/{gameId}/rooms/{roomId}`

## 接口列表

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/state` | 查询当前对局状态 |
| POST | `/start` | 房主开局 |
| POST | `/speak` | 提交发言并推进流程 |
| POST | `/vote` | 提交投票 |
| POST | `/night-action` | 狼人杀夜晚行动 |
| POST | `/action` | 统一动作入口，兼容发言、投票和夜晚行动 |

## 接口详情

### GET `/state` - 查询当前对局状态

**用途**：返回阶段、回合、当前行动位、日志、玩家列表、个人私有信息、夜晚待办等。

**请求参数**

Path params：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| gameId | String | 是 | 游戏标识（`undercover`/`werewolf`） | `undercover` |
| roomId | String | 是 | 房间 ID | `room-123` |

Headers：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| X-Auth-Token | String | 是 | 登录态 token | `<token>` |

**返回值（核心字段）**

| 字段 | 类型 | 说明 |
|---|---|---|
| phase | String | 当前阶段（WAITING/DESCRIPTION/VOTING/NIGHT/DAY_DISCUSS/DAY_VOTE/SETTLEMENT） |
| phaseEndsAt | String | 阶段结束时间 |
| players[].connectionStatus | String | 连接状态（ONLINE/DISCONNECTED/AI_TAKEOVER） |
| myRole/myWord | String | 当前玩家私有身份信息（仅本人或结算时可见） |
| pendingAction | Object | 狼人杀夜晚待办 |

**说明**
- 查询时会同步连接状态并处理超时自动推进（含断线托管逻辑）。
- 当状态在查询期间发生推进，服务端会同时推送 WS `state` 事件。

### POST `/start` - 房主开局

**用途**：初始化对局并进入首阶段。

**请求参数**

Headers：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| X-Auth-Token | String | 是 | 登录用户 token |

**返回值**
- 200：`GameStateResponse`

**错误码/常见错误**

| 错误码 | 说明 |
|---|---|
| 401 | 未登录 |
| 403 | 非房主 |
| 400 | 人数不足或状态非法 |

**WS 联动**
- 广播：`/topic/room/{roomId}/state`（`PHASE_CHANGE`）
- 私有：`/user/queue/private`（`ROLE_ASSIGNED`，包含 role/word/seatNumber）

### POST `/speak` - 提交发言

**请求体**

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| content | String | 是 | 发言内容 | `我描述的是一种食物` |

**返回值**
- 200：最新 `GameStateResponse`

**说明**
- 服务端会记录活跃状态并将玩家连接状态恢复为 ONLINE。
- 成功后推送 `state` 事件类型 `SPEAK`。

### POST `/vote` - 提交投票

**请求体**

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| targetPlayerId | String | 否 | 目标玩家 ID（弃票时可空） | `player-2` |
| abstain | Boolean | 否 | 是否弃票 | `false` |

**返回值**
- 200：最新 `GameStateResponse`

**说明**
- 在超时场景下，断线玩家会被自动记为弃票并写入日志。
- 成功后推送 `state` 事件类型 `VOTE`。

### POST `/night-action` - 狼人杀夜晚行动

**请求体**

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| action | String | 是 | `WOLF_KILL/SEER_CHECK/WITCH_SAVE/WITCH_POISON` | `SEER_CHECK` |
| targetPlayerId | String | 否 | 行动目标 | `player-3` |
| useHeal | Boolean | 否 | 女巫是否使用解药 | `true` |

**返回值**
- 200：最新 `GameStateResponse`

**错误码/常见错误**

| 错误码 | 说明 |
|---|---|
| 400 | 阶段错误、角色不匹配、目标无效 |

**WS 联动**
- 成功后推送 `state` 事件类型 `PHASE_CHANGE`。

### POST `/action` - 统一动作入口

**用途**：为 GameEngine 插件化后的玩法提供统一动作协议。当前支持谁是卧底、狼人杀和海龟汤，并保留旧接口兼容。

**请求体**

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| type | String | 是 | `SPEAK/VOTE/NIGHT_ACTION/ASK_QUESTION/FINAL_GUESS` | `SPEAK` |
| content | String | 否 | 发言、海龟汤提问或最终解答内容 | `这个人以前去过荒岛吗？` |
| targetPlayerId | String | 否 | 投票或夜晚行动目标 | `player-3` |
| abstain | Boolean | 否 | 投票是否弃票，`VOTE` 使用 | `false` |
| nightAction | String | 否 | 狼人杀夜晚行动类型 | `WOLF_KILL` |
| useHeal | Boolean | 否 | 女巫是否使用解药 | `true` |
| extra | Object | 否 | 后续新玩法扩展字段 | `{}` |

**返回值**
- 200：最新 `GameStateResponse`

**兼容策略**
- `SPEAK` 等价于旧 `/speak`。
- `VOTE` 等价于旧 `/vote`。
- `NIGHT_ACTION` 等价于旧 `/night-action`。
- `ASK_QUESTION` 用于海龟汤提问阶段，服务端会返回 AI 主持判定并追加 `extra.qaHistory`。
- `FINAL_GUESS` 用于海龟汤提交最终解答，结算后 `extra.solutionRevealed=true` 且返回 `extra.solution`。
- 新玩法应优先接入 `/action`，旧接口仅服务现有页面和兼容测试。

### 海龟汤状态字段

海龟汤状态沿用 `GameStateResponse`，额外字段放在 `extra`：

| 字段 | 说明 |
|---|---|
| soupTitle / soupPrompt | 当前题目的标题与汤面 |
| qaHistory | 玩家提问、主持回答、回答类型和命中线索 |
| confirmedClues | 主持已确认的公开线索 |
| questionCount / questionLimit | 当前提问数与上限 |
| solutionRevealed / solution | 仅结算阶段返回汤底 |
