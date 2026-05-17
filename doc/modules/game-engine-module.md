# GameEngine 插件化模块

## 模块作用

M3 将玩法规则从 `GamePlayService` 中拆出为可注册的 `GameEngine`。当前目标是让新增玩法先拥有稳定扩展点，同时保持狼人杀、谁是卧底的现有 API、状态结构、回放和 AI 质检行为不回退。

## 核心结构

| 组件 | 作用 |
|---|---|
| `GameEngine` | 每个玩法的统一接口，负责状态查询、开局、发言、投票、夜晚行动和统一 action 分发 |
| `GameEngineRegistry` | Spring 启动时注册所有 engine，按 `gameId` 查找，拒绝重复玩法 ID |
| `UndercoverGameEngine` | 谁是卧底 engine，声明阶段、角色和开局校验，并委托运行时规则 |
| `WerewolfGameEngine` | 狼人杀 engine，声明夜晚/白天阶段、角色和开局校验，并委托运行时规则 |
| `GameRuntimeSupport` | 当前迁移阶段的规则运行支撑层，保留原有流程、事件记录、AI 决策、结算和视图过滤逻辑 |
| `PlayerAction` | 统一动作请求体，用于 `/action` 兼容发言、投票和夜晚行动 |

## 当前边界

- `GamePlayService` 现在只做玩法查找、开局校验和兼容方法转发。
- 旧接口 `/speak`、`/vote`、`/night-action` 继续保留。
- 新接口 `/action` 已可提交：
  - `SPEAK`：`content`
  - `VOTE`：`targetPlayerId`、`abstain`
  - `NIGHT_ACTION`：`nightAction`、`targetPlayerId`、`useHeal`
- `GameState.data` 的既有键名保持不变，避免破坏前端页面、回放和历史测试。
- AI 决策仍复用 `AiDecisionService`，后续可继续把 prompt/adapter 做成 engine 元数据。

## 前端适配

- `frontend/src/hooks/useGameEngine.ts` 统一封装状态查询、开局和 `PlayerAction` 提交。
- `frontend/src/pages/games/registry.tsx` 作为玩法组件注册表，`Lobby` 不再在组件内部硬编码玩法页面映射。
- 现有 `UndercoverRoom` 与 `WerewolfRoom` 保留专用 UI，但动作提交已走统一 `/action`。

## 新玩法接入

新增玩法时，至少需要：

1. 新增一个 `GameEngine` 实现并注册为 Spring Bean。
2. 在 engine 中声明阶段、角色、开局校验和动作处理。
3. 在前端玩法组件注册表加入房间页面。
4. 在 API/模块文档中记录新增动作与视图字段。

## 后续优化

- 将 `GameRuntimeSupport` 中的具体规则继续下沉到各 engine 内部。
- 将 `RoomService` 的人数规则从硬编码完全切换到 engine metadata。
- 为 AI Adapter、Prompt 模板、合法性校验和兜底策略提供每玩法声明。
- 让服务端玩法元数据逐步成为前端创建房间配置的单一来源。
