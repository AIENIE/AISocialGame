# M3 GameEngine 插件化架构开发记录

> 完成日期：2026-05-17
> 对应提交：本次提交

## 目标

M3 的目标是把现有集中式游戏流程迁移到可注册的 GameEngine 架构，让后续新增玩法不再继续扩大 `GamePlayService` 的 `gameId` 分支复杂度。

## 代码改动

- 新增后端 engine 框架：
  - `GameEngine`
  - `GameEngineRegistry`
  - `ValidationResult`
  - `PhaseDefinition`
  - `RoleDefinition`
  - `UndercoverGameEngine`
  - `WerewolfGameEngine`
- 新增统一动作 DTO：
  - `PlayerAction`
- 重构游戏流程入口：
  - `GamePlayService` 改为通过 `GameEngineRegistry` 分发。
  - 保留旧 `state/start/speak/vote/nightAction` 服务方法签名。
  - 新增统一 `/action` HTTP 接口。
- 迁移运行时规则：
  - 新增 `GameRuntimeSupport` 作为当前阶段规则支撑层。
  - 保留原有谁是卧底、狼人杀的阶段推进、AI 行动、事件记录、回放归档和视图过滤行为。
- 前端适配：
  - 新增 `useGameEngine` hook。
  - 新增玩法页面 registry。
  - 谁是卧底、狼人杀页面改用统一 `/action` 提交玩家动作。
  - `Game` 类型补充 engine 阶段/角色元数据字段。

## 文档改动

- GameEngine 模块：[../../modules/game-engine-module.md](../../modules/game-engine-module.md)
- 游戏流程模块：[../../modules/gameplay-module.md](../../modules/gameplay-module.md)
- GamePlay API：[../../api/GamePlayController.md](../../api/GamePlayController.md)
- 模块索引：[../../modules/README.md](../../modules/README.md)
- 总路线图：[../../milestones.md](../../milestones.md)
- 集成测试记录：[../../test/integratedTest.md](../../test/integratedTest.md)

## 测试与验收

- 后端全量测试：`cd backend && mvn test`
- 前端构建：`cd frontend && pnpm build`
- Playwright 可重复验收：
  - `REAL_ACCEPTANCE=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:11030 pnpm test:acceptance`
  - 覆盖四场 AI 社交游戏闭环、服务端回放归档和统一 `/action` 接口。
- 浏览器一次性验收：
  - 通过 browser-use 访问本地前端，走查谁是卧底房间创建、补 AI、开局、发言/投票、结算和回放入口。

## 后续影响

- M4 海龟汤可以按新增 engine + 前端玩法页面方式接入。
- 后续应继续把 `GameRuntimeSupport` 中的玩法细节拆回各 engine，实现更彻底的规则隔离。
- AI Adapter 仍需在后续迭代中从 `AiDecisionService` 分支进一步演进为玩法级声明。


## 2026-09-22 原始交付项收尾

状态：本轮代码与离线门禁通过，最终运行及语义验收未完成；历史记录保留。

- GameRuleSet.definition、配置校验、observationRules、事实/声明提取归各插件；GameMetadataV2/GameService/RoomService 读取同一数据。
- 三款人数、阶段和公开配置统一；前端目录请求失败仅可重试，不能凭旧配置创建房间。
- 第四个仅测试插件验证注册、元数据、观察、动作、兜底与结算，不修改生产中央编排、不增加产品玩法。
- 旧 GameRuntimeSupport 限于历史路由，接入必要治理但未大规模重写。插件测试和三款规则 L2 通过；当前六局运行无回退仍待真实验证。

统一门禁：标准前后端 L2 后端 511 项（506 通过、5 项外部跳过），前端 58 项，零失败/错误；类型检查、构建、独立 Java 指标及 Python 6 项指标测试通过。实际 MySQL 完整迁移仍未验证；本轮未启用 caller、调用真实模型、应用共享迁移、提交、推送或部署。

详细实现、逐项测试、语义局限与仓库外证据见 [收尾报告](../../test/m1-m5-closure-20260922.md)，后续操作按 [执行清单](../../operations/m1-m5-live-execution-checklist.md)。总体 L4 未通过。
