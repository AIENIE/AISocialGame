# 海龟汤模块

海龟汤使用 `TurtleSoupRuleSet`，题目来自 `TurtleSoupCaseCatalog`。`TurtleSoupDefinition` 提供目录、配置和角色定义，前端 `TurtleSoupRoom` 加载共用的 `RoomExperience`。

玩家通过统一动作提交讨论、向主持提问、共同解答、请求提示或揭示汤底。服务端规则决定当前合法动作和阶段；主持任务使用独立的可见观察，题目事实与玩家视角分别投影。

AI 任务通过通用生成与持久任务链路执行。主持失败时展示请求失败并清除等待，保留探索次数和最终解答机会；队友失败时记录失败并结束当前机会，不补写发言或裁决。对局事件和结算使用统一归档链路，回放沿用权限和视角过滤。

规则回归位于 `TurtleSoupRuleSetTest`，完整流程由 `V2GameServiceIntegrationTest` 覆盖。接口见 [GamePlayController](../api/GamePlayController.md)。
