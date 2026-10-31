# 游戏流程模块

`GamePlayController` 使用 `V2GameService`，规则实现位于 `engine/v2/`。状态和日志读取、开局与统一动作分别使用 `/state`、`/logs`、`/start`、`/action`。

开局由房主触发，服务端验证房间状态、配置和人数，冻结规则与访问视角，再创建对局及初始事件。状态转换、持久事件、任务状态和结算在对应事务内提交。

动作携带 `requestId` 和服务端返回的 `expectedPhaseToken`。请求幂等及阶段校验防止重复执行和旧行动作用于新阶段。读写权限、私密房和回放视角由服务端判定。

`AiTurnCoordinator` 认领持久 AI 任务，构造观察并调用 `AiTurnGenerator`。原请求未决时保存检查点并查询原结果；已过期行动不能提交。任务失败与规则推进分别记录。

`GameEventRecorder` 持久化事件，`ReplayArchiveService` 生成归档；`StatsService` 负责结算。房间事件通过 `GamePushService` 推送，前端以当前 HTTP 状态恢复并去重实时消息。

三款规则的集成回归位于 `V2GameServiceIntegrationTest`，动作接口见 [GamePlayController](../api/GamePlayController.md)。
