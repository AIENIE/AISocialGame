# AI 决策模块

## 模块作用

- 将 AI 玩家从简单补位升级为基于局面上下文的决策者。
- 覆盖谁是卧底与狼人杀的 AI 发言、投票、夜晚行动。
- 保持 AI 明确标识，但通过 Persona、人设记忆种子、局内发言/投票上下文提升拟人程度。

## 核心流程

1. `GamePlayService` 在自动发言、自动投票、自动夜晚行动节点调用 `AiDecisionService`。
2. `AiDecisionService` 构造 `AiGameContext`，只暴露该 AI 玩家可见的信息：
   - 自身身份/词语；
   - 存活玩家、座位、连接状态；
   - 已公开的死亡身份、狼人队友信息、预言家自己的查验结果；
   - 局内发言、投票日志和系统事件。
3. `AiBeliefService` 与 `AiReflectionService` 注入信念、局内短期记忆和跨局 Persona 记忆。
4. 决策服务调用 ai-service，要求输出紧凑 JSON：
   - 发言：`{"content":"...","reason":"...","confidence":0.7,"evidence":["..."],"reflection":"..."}`
   - 投票：`{"targetSeat":3,"reason":"...","confidence":0.7,"evidence":["..."],"reflection":"..."}`
   - 夜晚行动：`{"action":"WOLF_KILL","targetSeat":3,"useHeal":false,"reason":"...","confidence":0.7,"evidence":["..."],"reflection":"..."}`
5. 服务端解析并校验座位、目标和行动合法性；AI 服务失败或输出非法时使用确定性规则兜底，确保对局不会卡死。
6. `AiQualityService` 与 `AiDecisionTraceService` 记录 fallback、质量 flags、耗时、模型、信念和记忆快照。

## 当前实现位置

- `backend/src/main/java/com/aisocialgame/service/ai/AiDecisionService.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiGameContext.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiPlayerInfo.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiDecisionResult.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiBeliefService.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiQualityService.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiReflectionService.java`
- `backend/src/main/java/com/aisocialgame/service/ai/AiDecisionTraceService.java`
- `backend/src/main/resources/prompt.yml`
- `backend/src/test/java/com/aisocialgame/AiDecisionServiceTest.java`

## 已支持的行为

- 谁是卧底：
  - 根据词语、身份、人设和前序发言生成描述；
  - 根据本轮描述和历史投票选择投票目标。
- 狼人杀：
  - 根据身份、昨夜事件、发言历史和人设生成白天发言；
  - 根据讨论选择放逐目标；
  - 狼人/预言家/女巫可通过 AI 决策夜晚目标。

## 设计边界

- 默认不隐藏 AI 身份，前端仍展示 AI 标记。
- Prompt 层不得要求 AI 冒充真人，也不得泄露服务端不可见信息。
- 兜底策略追求流程稳定和确定性，不追求最强博弈；AI 质量应主要由上下文、Persona 和模型输出提升。
- 完整 AI trace 只面向服务端和管理端，玩家界面不展示隐藏信息、原始 Prompt 或内部推理链。

## 验证方式

```bash
cd backend
mvn test -Dtest=AiDecisionServiceTest,GamePlayServiceUndercoverTest,GamePlayServiceWerewolfTest
```

真实 ai-service 联调：

```bash
set -a && source ../env.txt && set +a
REAL_AI_INTEGRATION=1 mvn test -Dtest=AiDecisionRealIntegrationTest
```
