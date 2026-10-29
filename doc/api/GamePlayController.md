# GamePlayController

基础路径：`/api/games/{gameId}/rooms/{roomId}`。入口使用当前登录用户身份，执行统一交给 `V2GameService`。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/state` | 当前用户可见的对局状态 |
| GET | `/logs` | 按公开游标分页读取日志 |
| POST | `/start` | 房主验证配置和人数后开局 |
| POST | `/action` | 提交当前规则允许的动作 |

`/logs` 接收可选 `before` 游标与 `size`，返回 `items`、`nextCursor` 和 `hasMore`。游标对应公开事件序列。

统一动作包含 `type`、`requestId` 与 `expectedPhaseToken`；后者使用当前状态返回的 `extra.phaseToken`。按动作需要提交 `content`、`targetPlayerId`、`nightAction`、`useHeal` 或 `abstain`。重试同一次提交复用请求 ID，新的行动使用新 ID。

合法动作由状态中的 `extra.legalActions` 提供。服务端核查当前阶段、行动者、目标、规则、权限及治理控制，返回更新后的 `GameStateResponse`。私密信息和回放视角以服务端过滤结果为准。

接口退役回归由 `RetiredRoutesTest` 验证，当前三款游戏流程由 `V2GameServiceIntegrationTest` 覆盖。
