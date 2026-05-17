# 游戏流程模块（狼人杀 / 谁是卧底）

## 模块作用

- 提供“开局 -> 阶段推进 -> 投票/夜晚行动 -> 结算”的统一对局主流程。
- 在 v2 中补充实时推送、断线状态同步、超时托管（AI takeover）与自动推进能力。
- M3 后 `GamePlayService` 作为兼容编排层，具体玩法入口由 `GameEngineRegistry` 分发。

## 功能点清单

| 功能点 | 作用 | 实现位置 |
|---|---|---|
| 状态查询 `/state` | 返回当前阶段、回合、当前座位、日志、玩家私有身份信息、夜晚待办 | `backend/src/main/java/com/aisocialgame/controller/GamePlayController.java`、`backend/src/main/java/com/aisocialgame/service/GamePlayService.java` |
| GameEngine 分发 | 将具体玩法动作分发到注册 engine，减少主流程 `gameId` 分支 | `backend/src/main/java/com/aisocialgame/engine/*.java` |
| 开局与私有身份下发 | 房主开局后完成角色/词语分配，并通过 WS 私有队列下发身份信息 | `backend/src/main/java/com/aisocialgame/service/GamePlayService.java`、`backend/src/main/java/com/aisocialgame/websocket/GamePushService.java` |
| 发言/投票/夜晚行动 | 旧接口继续兼容，统一 `/action` 作为插件化动作入口 | `backend/src/main/java/com/aisocialgame/controller/GamePlayController.java`、`backend/src/main/java/com/aisocialgame/engine/GameEngine.java` |
| AI 行为质检挂载 | AI 自动发言/投票/夜晚行动后写入 trace，并把安全摘要写入日志 metadata | `backend/src/main/java/com/aisocialgame/service/ai/*`、`backend/src/main/java/com/aisocialgame/model/AiDecisionTrace.java` |
| 断线检测与托管 | 按连接活跃时间更新 `ONLINE/DISCONNECTED/AI_TAKEOVER`，超时后自动托管/弃票 | `backend/src/main/java/com/aisocialgame/websocket/PlayerConnectionService.java`、`backend/src/main/java/com/aisocialgame/service/GamePlayService.java` |
| 前端房间实时渲染 | 取消轮询，改为 WS 事件驱动刷新；展示倒计时、阶段过渡、连接状态、聊天面板 | `frontend/src/hooks/useGameSocket.ts`、`frontend/src/pages/games/UndercoverRoom.tsx`、`frontend/src/pages/games/WerewolfRoom.tsx`、`frontend/src/components/game/*` |

## 关键流程

1. 房主调用 `/start`，后端初始化 `GameState` 并推送 `PHASE_CHANGE`。
2. 玩家通过 `/action` 或旧 `/speak`、`/vote`、`/night-action` 交互，后端保存状态并推送 `state` 事件。
3. 玩家连接状态由 STOMP 连接 + 活跃打点共同维护，断线后进入 `DISCONNECTED`，超时后进入 `AI_TAKEOVER`。
4. 阶段超时或操作完备时自动推进，结算后记录统计并回写房间状态。
5. AI 动作会同步更新 `GameState.data.aiBeliefs`、`GameState.data.aiShortMemories` 与后台 trace，不改变玩家端可见信息边界。

## 本轮新增修复（2026-03-04）

1. 断线判定阈值改为可配置：
   - 配置项：`connection.disconnect-threshold-seconds`
   - 默认值：`60`
   - 变更目标：降低短时抖动导致的误判离线。

2. 狼人杀夜晚自动补行动策略优化：
   - 当存在在线真人夜晚角色（狼人/预言家/女巫）时，不再提前由系统自动补齐该角色夜晚动作；
   - 仅对“无在线真人参与”的角色执行自动补动作；
   - 变更目标：避免真人夜晚还在场时被系统越权代操作。

3. 满房重连玩家识别修复：
   - `RoomService.joinRoom` 调整为“先匹配已在房间玩家，再检查满房”；
   - 变更目标：确保已在房玩家重连后仍能拿到 `myPlayerId/mySeatNumber`，避免出现“轮到发言但无输入框”。

## M3 插件化入口（2026-05-17）

- 新增 `GameEngine` 与 `GameEngineRegistry`。
- 新增 `UndercoverGameEngine`、`WerewolfGameEngine`。
- `GamePlayService` 保留旧服务方法签名，但通过 registry 分发到具体 engine。
- 新增 `PlayerAction` 与 `/action`，前端房间页已通过 `useGameEngine` 使用统一动作入口。
- 当前 `GameRuntimeSupport` 仍保留迁移阶段的规则支撑逻辑，后续可继续拆入各 engine。

## 相关文件

- `backend/src/main/java/com/aisocialgame/dto/GamePlayerView.java`：响应新增 `connectionStatus`。
- `backend/src/main/java/com/aisocialgame/model/GamePlayerState.java`：新增连接状态与活跃时间字段。
- `backend/src/main/java/com/aisocialgame/dto/ws/*`：WS 推送事件 DTO。
- `frontend/src/types/index.ts`：新增 WS 事件与聊天消息类型。

## 验收方式（2026-03-04）

- 本模块不再依赖自动化 4 局脚本进行最终验收。
- 采用 subagent + Playwright 真人流程完成 4 场完整对局：
  - 谁是卧底：1 用户 + AI
  - 谁是卧底：3 用户 + AI
  - 狼人杀：1 用户 + AI
  - 狼人杀：3 用户 + AI
- 每场需记录：
  - 人类玩家发言、投票、夜晚行动与决策依据
  - AI 角色发言与行为
  - 系统日志与结算结果
- 报告目录：`result/game-reports/<run-id>/`（本地产物，不入库）。
