# 游戏规则模块

游戏统一由 `GameRuleSet`、`V2GameService` 与 `AiTurnCoordinator` 执行。Spring 注册规则实现，重复玩法 ID 在初始化时拒绝。

| 玩法 | 规则 | 定义与题库 |
|---|---|---|
| 谁是卧底 | `UndercoverRuleSet` | `UndercoverDefinition`、`UndercoverWordCatalog` |
| 狼人杀 | `WerewolfRuleSet` | `WerewolfDefinition` |
| 海龟汤 | `TurtleSoupRuleSet` | `TurtleSoupDefinition`、`TurtleSoupCaseCatalog` |

规则的 `definition()` 是人数、配置、阶段、角色及规则版本的来源。`GameMetadataV2` 与 `GameService` 提供目录和在线人数，`RoomService` 使用同源定义校验配置。

`GamePlayController` 将开局、状态与统一动作交给 `V2GameService`。规则只执行本地状态转换；编排层负责事务、事件、归档与结算，AI 工作由持久任务独立执行。

`ObservationFactory` 根据规则提供的可见事实与配置投影构造模型观察。规则实现负责合法动作、阶段推进、私有视角及失败后的机会推进。

`PluginClosureTest` 使用第四个测试规则验证目录、开局、观察、动作、结算和回放，无需修改中央玩法分支。当前接口见 [GamePlayController](../api/GamePlayController.md)。
