# AI 拟人质量闭环模块

## 模块作用

M1 将 AI 玩家从单次 Prompt 决策升级为“信念 -> 记忆 -> 决策 -> 质检 -> 反思 -> 再利用”的闭环。模块覆盖谁是卧底和狼人杀，目标是在 AI 身份明示的前提下提升发言稳定性、投票依据、夜晚行动合法性和 Persona 连续性。

## 代码架构

| 层级 | 主要类 | 作用 |
|---|---|---|
| 信念构造 | `AiBeliefService` | 基于玩家、日志、公开行动生成每个 AI 的可见信念快照，区分狼人杀公开信息、狼人阵营信息和个人技能信息。 |
| 决策编排 | `AiDecisionService` | 组装 Persona、信念、短期记忆和跨局记忆，调用 ai-service，解析 JSON，并在失败时使用规则兜底。 |
| 质量检测 | `AiQualityService` | 标记 fallback、疑似泄密、重复模板、无依据投票、非法目标等质量问题。 |
| 反思记忆 | `AiReflectionService` | 生成局内短期反思，维护 `ai_persona_memories` 中的跨局 Persona 记忆。 |
| 追踪审计 | `AiDecisionTraceService` | 将每次 AI 决策写入 `ai_decision_traces`，供管理端质检、回放和后续 M2 事件流使用。 |

## 数据流

1. `GamePlayService` 在 AI 自动发言、自动投票、夜晚补行动时调用 `AiDecisionService`。
2. `AiDecisionService` 先构造 `belief`，并读取 `aiShortMemories` 与 `ai_persona_memories`。
3. Prompt 输入包含：玩法、阶段、回合、自身可见信息、玩家列表、最近发言/投票/事件、Persona、人设记忆、信念快照。
4. ai-service 返回紧凑 JSON；旧字段仍兼容，新字段包括 `confidence`、`evidence`、`reflection`、`qualityNotes`。
5. 服务端执行合法性校验和质量检测；失败或非法时使用确定性兜底。
6. 决策结果写入 `GameState.data.aiBeliefs`、`GameState.data.aiShortMemories`、`ai_decision_traces`、`ai_persona_memories` 与对应 `GameLogEntry.metadata` 的安全摘要。

## 隐私与可见性

- AI 仍然明确标识为 AI，系统不要求 AI 冒充真人。
- 玩家可见日志只展示发言、投票完成、公开系统事件。
- `GameLogEntry.metadata` 只保存 trace id、fallback、质量 flags、模型、耗时、简短依据和安全反思。
- 完整信念、记忆、原始输出只进入服务端 trace 与管理端接口，不进入玩家界面。
- Persona 记忆只按 Persona/玩法/角色维度沉淀策略与风格，不保存用户隐私。

## 数据表

- `ai_decision_traces`：每次 AI 决策一条记录，保存动作、模型、耗时、fallback、质量 flags、信念快照、记忆快照和输出摘要。
- `ai_persona_memories`：每个 `persona_id + game_id + role_key` 一条跨局记忆，保存策略、错误、口吻模式和累计次数。

## 管理端接口

- `GET /api/admin/ai/decision-traces`：按 `roomId`、`gameId`、`personaId`、`action`、`fallback`、`qualityFlag` 分页筛选。
- `GET /api/admin/ai/persona-memories`：查看跨局 Persona 记忆，可用 `personaId` 过滤。
- `POST /api/admin/ai/persona-memories/{id}/reset`：清空单条 Persona 记忆，用于运营纠偏或坏记忆回滚。

## 验证方式

```bash
cd backend
mvn test -Dtest=AiDecisionServiceTest,GamePlayServiceUndercoverTest,GamePlayServiceWerewolfTest
```

真实 ai-service 联调需显式启用：

```bash
cd backend
set -a && source ../env.txt && set +a
REAL_AI_INTEGRATION=1 mvn test -Dtest=AiDecisionRealIntegrationTest
```
