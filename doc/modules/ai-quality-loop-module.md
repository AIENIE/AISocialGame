# AI 拟人质量闭环模块

## 卧底真实调用（2026-10-13）

卧底 v2 的描述、补充描述和投票使用配置中的真实模型，经过 `AiGrpcClient.undercoverChatCompletions` 与 `BoundedAiClient`；其他游戏和通用 AI HTTP 接口保持原有入口。开局词库和词语知识保留，卧底新旧路径不再拼接预置台词或选择固定兜底投票目标。旧版对局调用失败只跳过或弃票；新开局使用 v2。

每个动作首次加最多三次重试，格式/动作修正共用上限；所有尝试共用当前回合时间和最多 50 秒执行窗口。每次重试前重查回合有效性，迟到结果不提交。只对尚未发送的临时故障重发推理；鉴权、额度、能力不足及结果不明立即结束。模型失败通过系统 SKIP 事件显示 REQUEST_FAILED，超时使用 TIMEOUT，不生成角色发言；投票失败仅该角色弃权。

预算实现择取历史提交 `770d64e`。先 PrepareCallBudget，按权威最高用量原子预留，再携凭据调用；每个预算最多一次供应商发送。状态成功后按真实 usage 结算；临时积分退款保持原到期日。结果未知保留 HELD，后台只查询状态，不重新推理。现有鉴权、内容检查和调用次数上限继续生效。

配套结构为 `ai_credit_reservations`、`credit_temp_lots`，原始 `20261002_ai_credit_escrow.sql` 保持原字节，在本分支迁移序列末尾登记。存量库先核对结构及已有执行证据，不重复执行或清空历史预留。配置默认 budget-enabled=false，输出上限 1024，状态查询间隔 30 秒；使用标准配置对映射。

离线测试使用明确的模拟模型响应，不能证明真实语义表现。L2 后更新本地实例，由用户新开局验收；不自动付费调用，不修改共享 ai-service 配置或发布其他环境。

## 当前版本（2026-09-22）

当前 v2 路径：V2GameService → ObservationFactory → AiTurnGenerator → 规则合法提交/记忆/trace 同事务。AiMemoryServiceV2 使用局内 v4，来源分类与明确动作承诺保留 R1/R2 规则；AiRoundReflection 生成最近四轮的有界本人反思，未知真伪/计划不猜测。跨局候选须经管理员批准。config.aiDifficulty 与完整 Persona 独立，1 简单、2 娱乐、3 进阶；提示词 social-v2.9、模型输入 v3。180 个离散决策工具与六局脚本已准备，真实语义尚未验收。

模型投影增加 `conversation`：各规则适配器从已过滤观察提供当前交流场景、合法动作对应的可选目的/篇幅建议及直接质询事件索引。通用默认实现支持其他插件，主持不注入玩家交流上下文；完整持久观察不变。篇幅没有最低字数，也没有新增风格拒绝、截断或模型调用。输出仍使用 action.content，旧 speech 兼容；人格、记忆、权限及降级路径不变。见[正常交流优化报告](ai-quality-loop-module.md)。

当前测试、限制及数据迁移见 [M1～M5 收尾报告](ai-quality-loop-module.md)。**下文为早期实现说明，涉及旧运行时的描述仅适用于历史兼容路径，不作为当前 v2 架构依据。**


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
set -a && source ../env.local && set +a
REAL_AI_INTEGRATION=1 mvn test -Dtest=AiDecisionRealIntegrationTest
```


## 当前验收与停止边界

现行正常生成沿用 social-v2.9 / 输入 v3，突出交流目的与发言尺度；人格与记忆边界保留，降级优化留待后续。离线结构、规则与工程门禁通过不证明真实语义质量或多人格持续表现。最近真实先导 12 项中 11 项正常（其中 4 次修正）、1 项降级；正常输出四维均分均低于 4，因此其余 84 项按门槛停止。M1-R5 和总体 L4 仍未通过。

原对照与对局账本不能当实时可用预算。caller 状态、共享环境目标和迁移差异未核实前不连接未知数据库、不启用调用、不扩预算。模型调用和真实局验收须按现有授权进行，不把结构引用命中率当语义正确率，不用代理评阅替代独立人工盲评。评阅尺度见[人工评阅规范](../ai-realism-manual-review-rubric.md)，阶段状态只在[里程碑](../milestones.md)维护。


## 卧底推理与并发调用（2026-10-13）

卧底使用 social-v2.10 专用提示及显式 thinking，请求输出上限为 8192 token（包含内部推理），最终描述字数规则不变。新房间描述默认 90 秒，补充描述/投票/复投 60 秒；已有显式描述时长保持。任务最多 89 秒，并受原阶段截止约束；重试、排队均不续时。其他游戏保留原时限与执行池。

投票阶段一次建立各 AI 的可见信息快照；独立 8 线程执行池按房间轮转，投齐或截止后一次揭晓。原子积分预留仍使用服务端最高用量，当前路线 1,048,576 输入 + 8,192 输出 = 1,056,768 积分/请求。被正在执行的调用占用额度时排队、释放线程并保留请求 ID/重试进度；真正不足或仅有未知预留阻挡时结束行动，不补积分、不用估算降低保证。低余额不能保证所有 AI 在截止前完成。

一元响应的可选 finish_reason/reasoning_tokens 随预算结果持久保存；缺失表示未知，reasoning_tokens 不重复计费。明确 length 截断立即停止该行动且不重试；未知空正文记录 EMPTY_MODEL_RESPONSE，其他可重试错误仍最多首次加三次。失败公开为系统原因（输出上限/额度不足/待对账/请求失败/超时），不生成预置角色台词。已发送未知请求只查原状态；迟到结果可结算但不能应用到过期阶段，旧未知预留不自动退款。

离线验证覆盖并发提交、额度等待与恢复、截断、可选元数据、时限及历史兼容；付费模型表现仍由用户新开局验收，自动检查不证明真实语义质量。
