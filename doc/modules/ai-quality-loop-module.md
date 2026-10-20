# AI 拟人质量闭环模块

## 按功能配置模型（2026-10-19）

后台“模型配置”页面手动同步 ai-service 调用方可见的文本模型及思考能力，分别绑定谁是卧底、狼人杀、海龟汤玩家与主持人。首次导入现有默认模型，统一跟随服务默认思考行为。能力未知只提供默认选项；同步发现模型或强度失效时保留原配置并提示调整，不自动替换。

新旧对局入口共用功能配置。v2 任务首次认领时持久化模型/思考快照，修正及预算恢复不重新选型；每次外发前按最新已同步目录检查快照是否仍有效。管理员保存只影响后续新任务。未列入四项的插件玩法继续沿用环境默认模型。原预算限制、次数上限、回合时限及对账顺序继续生效，狼人杀与海龟汤的推理能力保持原启用状态。接口与迁移见 [管理 AI 接口](../api/AdminAiController.md#模型配置)。

## 卧底真实调用（2026-10-13）

卧底 v2 的描述、补充描述和投票使用配置中的真实模型，经过 `AiGrpcClient.undercoverChatCompletions` 与 `BoundedAiClient`；其他游戏和通用 AI HTTP 接口保持原有入口。开局词库和词语知识保留，卧底新旧路径不再拼接预置台词或选择固定兜底投票目标。旧版对局调用失败只跳过或弃票；新开局使用 v2。

每个动作首次加最多三次重试，格式/动作修正共用上限；所有尝试共用当前回合时间和最多 50 秒执行窗口。每次重试前重查回合有效性，迟到结果不提交。只对尚未发送的临时故障重发推理；鉴权、额度、能力不足及结果不明立即结束。模型失败通过系统 SKIP 事件显示 REQUEST_FAILED，超时使用 TIMEOUT，不生成角色发言；投票失败仅该角色弃权。

预算实现择取历史提交 `770d64e`，金额口径于 2026-10-20 接入。先以 billing_currency=CNY 准备预算，按 ai-service 冻结价格返回的 max_credit_points 原子预留，再携凭据调用；每个预算最多一次供应商发送。成功按费用回执 credit_points 结算，1 元人民币=100 积分；整数钱包每笔按实际费用×100 向上取整一次，供应商费用仍独立保存六位小数。token 上限只约束用量，不能充当钱包积分。临时积分退款保持原到期日；结果未知保留 HELD，后台只查询状态，不重新推理。生成结束后的只读对账使用独立的 5 秒查询时限。现有鉴权、内容检查和调用次数上限继续生效。

配套结构为 `ai_credit_reservations`、`credit_temp_lots`；增量 `20261020_ai_budget_money.sql` 为预留增加可空的 billing_quote_base64，用于保存该笔金额报价。历史 NULL 行继续按原 token 口径核对，原始迁移和历史流水不改写。存量库先核对结构及已有执行证据，不重复执行或清空历史预留。配置默认 budget-enabled=false，输出上限 8192，状态查询间隔 30 秒；使用标准配置对映射。

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
- 完整信念、记忆、原始输出进入服务端 trace 与管理端接口；local 房间调试按下方明确的环境与参与者边界提供，普通游戏界面仍只展示公开资料。
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

投票阶段一次建立各 AI 的可见信息快照；独立 8 线程执行池按房间轮转，投齐或截止后一次揭晓。原子预留按当前请求的人民币价格上界转换为积分，不再将 1,048,576 输入 + 8,192 输出错误地计为 1,056,768 积分。被正在执行的调用占用额度时排队、释放线程并保留请求 ID/重试进度；真正余额不足返回 AI_CREDIT_INSUFFICIENT，不把新请求误标为旧请求待对账。旧未知预留仍保留，余额足以覆盖新请求金额上界时正常调用。低余额不能保证所有 AI 在截止前完成。

一元响应的可选 finish_reason/reasoning_tokens 随预算结果持久保存；缺失表示未知，reasoning_tokens 不重复计费。明确 length 截断立即停止该行动且不重试；未知空正文记录 EMPTY_MODEL_RESPONSE，其他可重试错误仍最多首次加三次。失败公开为系统原因（输出上限/额度不足/待对账/请求失败/超时），不生成预置角色台词。发送 RPC 之前发生的本地准入、次数上限或时限错误保留原原因，不误标为已发送待对账；该笔预留仍须等服务端确认预算未发送后释放。已发送未知请求只查原状态；迟到结果可结算但不能应用到过期阶段，旧未知预留不自动退款。

离线验证覆盖并发提交、额度等待与恢复、截断、可选元数据、时限及历史兼容；付费模型表现仍由用户新开局验收，自动检查不证明真实语义质量。


## local 房间 AI 调试工作台（2026-10-20）

房间详情新增 `aiDebugAvailable`。后端真实 ENV=local 且当前用户属于房间时，共用房间页显示调试按钮，打开接近全屏的工作台；谁是卧底、狼人杀、海龟汤的玩家、主持和托管任务共用同一采集路径。桌面按回合导航、角色行动、调用详情分栏；平板合并回合导航，手机分级阅读。摘要每两秒更新，正文按需加载，手动选择后保持阅读位置；关闭弹窗停止查询。

房间路径 `/api/games/{gameId}/rooms/{roomId}/debug/ai` 提供只读 `instances`（分页）、`summary?instanceId`、`turns?instanceId&round&phase&actorId&status&page&size` 和 `turns/{jobId}`。仅复用已登录房间参与者权限，公共房间的外部旁观者也不能读取；校验房间、玩法和记录归属。非 local 返回 404。房间失效沿用既有 410 规则，不新增独立历史入口。

`local_ai_debug_turns` 保存逐任务元数据，私有 payload 的 attempts 按尝试序号唯一存储实际消息、原始响应、解析结果、上游思考、用量、修正与错误；旧任务只展示已保存观察与决策资料，不重建历史请求。采集写入使用独立短事务，游戏提交后的状态与采用动作在提交后记录；采集失败显示缺失提示，不改变游戏或重试模型。预算等待复用原尝试，状态查询和重放只更新该记录；迟到结果只补充资料。历史预算请求保持原调试标志和请求指纹。

思考文本或摘要依赖已支持 include_debug 的 local ai-service；上游没有返回文本、网关旧版本或历史回执缺字段均明确显示缺失，不根据 token 数补造。输入已包含缓存读取与创建，输出已包含推理；总量只取输入加输出。缓存分项缺失、推理缺失和未知权威性与实际零值区分，部分用量显示不完整。旧响应未标注来源的零值按未知处理，预算状态的权威确认可证明真实零值，重复回执不抹去这项确认。调试原文不进入公开事件、普通日志或角色记忆，不采集请求头、密钥和 provider_state。

迁移 15 为 `backend/sql/20261020_local_ai_debug.sql`，只增加独立表；当前结构与执行清单同步，冻结历史不改写。记录跨页面刷新、后端重启和同房间不同对局保留，无自动清理，存储会随使用增长。

2026-10-20 已启用实际 local：共享 ai-service 平台版本 `20261020009`（固定源码 `ed226b82e9efddf5ade61260d7b8c40d4489c247`，发布 ID `7cf59d46-f8f9-432f-922f-d30ebe380423`）；AISocialGame 原生应用更新至 `f746c96ec4d07ab5e4ae90252c6f8e096f902206` 并通过标准脚本重启。第 15 项增量 SHA-256 `2b1a1147c2584c22a1dcf228dce730c7d6ed63c59191d831f1d84f859748e0d6`，local aisocialgame 数据库执行 ID `b13c6d19-5f67-4c13-b81b-8cee66f65adc`；28 列、索引和字符集核验通过，原有 79 个房间、299 条任务数量未变。

既有测试账号真实登录后，房间调试入口、同房间三局历史选择、回合/角色用量与详情读取通过，历史原文缺失提示正常，浏览器无错误；匿名读取返回 401。实际 AISocialGame HMAC/TLS 目录读取及网关调试流式拒绝核对通过，无生成或预算准备。新调用从启用后完整采集，旧记录不补造；本次未发起真实模型生成验收。运行结果和截图保存在仓库外 `aienie-runtime/evidence/product-artifacts/aisocialgame/local-room-ai-debug-20261020/activation/`。历史完整迁移链的既有字面量反斜杠 n 错误仍保留，本次仅执行已验证的第 15 项增量，不重放历史链。
