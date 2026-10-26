# AI 实验室接口

用户端 `/api/lab` 沿用登录用户会话；管理端 `/api/admin/lab` 沿用管理员会话、来源校验和操作二次确认。

| 方法 | 路径（相对用户端前缀） | 行为 |
|---|---|---|
| GET | /experiments?page=0&size=20 | 分页实验摘要 |
| GET | /experiments/{id} | 费用、进度、活动桌、当前用户报名和评测摘要 |
| GET | /experiments/{id}/matches?page=0&size=20 | 对局分页，不返回冻结输入和复盘原文 |
| POST | /experiments/{id}/enrollment | 报名下一局 |
| POST | /experiments/{id}/enrollment/confirm | 在 30 秒窗口确认 |
| DELETE | /experiments/{id}/enrollment | 取消尚未开局的报名 |

管理端同样提供实验列表、详情和对局分页，另有：

| 方法 | 路径（相对管理端前缀） | 行为 |
|---|---|---|
| GET / POST | /agents | 查询 / 新建独立 AI 档案 |
| PUT | /agents/{id} | 更新供新实验使用的档案 |
| DELETE | /agents/{id} | 删除档案，保留历史实验、回放和经验快照 |
| POST | /experiments | 创建草稿 |
| PUT | /experiments/{id}/budget | 以 `{budgetPoints}` 修改费用预算，返回更新后的管理员详情；所有状态均可修改 |
| POST | /experiments/{id}/control/{action} | start、resume、pause、stop |
| GET | /experiments/{id}/experiences?page=0 | 经验版本、来源及证据分页 |
| GET | /experiments/{id}/matches/{matchId}/spectate | 绑定实验的只读公开观战视图，仅管理会话 |
| GET | /experiments/{id}/matches/{matchId}/diagnostics | 绑定实验的管理员只读诊断，含失败位置、可用的失败动作和原请求账务，不返回提示词或内部推理 |
| POST | /experiments/{id}/rollback/{experienceId} | 暂停或完成状态下生成回退版本 |
| POST | /experiments/{id}/retry-review | 管理员重试失败复盘并继续 |
| POST | /experiments/{id}/matches/{matchId}/reviews | 暂停实验的单次复盘，`{operationId,coach}`；同一操作及参数返回原任务，参数冲突返回 409 |
| GET | /experiments/{id}/matches/{matchId}/reviews/{operationId} | 单次复盘状态、原请求、流式进度、首正文 TTFT、权威用量、实际费用及发布版本 |
| POST | /experiments/{id}/evaluations | 发起冻结对手的纯 AI 成对评测 |
| GET | /experiments/{id}/strategies | 狼人杀共享版本、内容差异、证据、评测与评审 |
| POST | /experiments/{id}/strategies/{versionId}/assessment | 记录 ruleError、evidenceLeak、severeContradiction 三个布尔值及 notes；任一缺陷阻止试用发布 |
| POST | /experiments/{id}/scenario-evaluations | `{operationId,candidateStrategyVersionId}` 创建冻结保留题的初始/候选评测，同操作恢复原结果 |
| GET | /experiments/{id}/scenario-evaluations/{operationId} | 情境运行状态、进度、逐题结果、原请求检查点和费用 |
| POST | /strategy-publications | `{operationId,action,strategyVersionId?,personaIds,mode:"TRIAL"}`，action 为 PUBLISH/WITHDRAW |
| GET | /strategy-publications/{operationId} | 原不可变发布或撤回回执 |
| POST | /experiments/{id}/training-runs | `{operationId,goal:"RULES_EVIDENCE",externalDiagnostics?:[{requestId,budgetId}]}`，冻结 V2 材料与课程；相同参数返回原运行，不同参数返回 409 |
| GET | /experiments/{id}/training-runs/{operationId} | 阶段、任务、实际输入、流式进度、TTFT、费用拆分、候选与对照结论 |
| POST | /experiments/{id}/training-runs/{operationId}/control/{action} | start、start-one、pause、resume、stop；start-one 完成首个代表案例后暂停 |
| GET | /experiments/{id}/training-runs/{operationId}/cases | 冻结开发/保留/排除材料、来源分组及覆盖；仅管理员可读 |

V2 运行状态为 DRAFT / RUNNING / PAUSED / COMPLETED / STOPPED，阶段为 PRACTICE / MERGE / REHEARSAL / HOLDOUT / GAMES / REPORT。任务状态为 PENDING / RUNNING / WAITING / SUCCEEDED / FAILED / BLOCKED / SKIPPED。`COMPLETED` 仅表示本轮已结束，是否改善以 assessment 为准；无可靠更新、不满足继续条件均可正常结束。`cost.spentPoints` 为实验钱包实扣，`externalPoints` 为已确认但未重复扣钱包的外部计价，`totalKnownPoints` 为停止线累计，`pendingCalls` 不计作零。旧未知请求不被课程重发。

`diagnostics.calls[].executionState` 为 NOT_STARTED / RUNNING / TERMINATED / UNKNOWN；TERMINATED 必须同时有权威 executionEndedAtEpochMs，仅证明网关执行已结束，不保证供应商停止计算。错误回复、RECONCILIATION_REQUIRED、租约过期都不能替代该证明。付费重做受一次/教学任务、两次/运行限制，记录被替代请求；格式修正不另增次数。普通房间发布沿用同一明确版本的 PUBLISH/WITHDRAW 回执，不随课程完成自动发布。

AI 档案请求：`name, personaId, selection{modelKey,thinkingMode,thinkingEffort}`。思考能力遵循已有模型目录；未知能力仅允许 DEFAULT。失效选择保留并报错。

单次复盘复用历史复盘的原始 system/user 输入，任务写入对局 JSON，独立于自动开局游标。operationId 为 UUID；返回 taskId、requestId、status，状态为 QUEUED / RUNNING / WAITING / SUCCEEDED / FAILED / BLOCKED。仅确认未发送才按 2、5、10 秒创建下一传输身份；已发送请求只恢复原结果，格式失败不追加付费修正。完整结果通过全部参与者与证据校验后，在同一事务发布新版并关联各自上一版；失败保留原文和现有经验。任务结束后实验保持暂停，不启动下一局。`scripts/windows/Invoke-LabReview.ps1` 在 POST 前保存 operationId，重试相同请求可找回原任务，并导出本次单局验收结果。

教练使用真正的上游流式：首个非空正文最多 600 秒、首事件后连续无数据最多 120 秒、整次最多 1200 秒、输出上限 32768。TTFT 从实际发送上游到首正文计算，首事件另记；心跳、响应头和 STARTED 不计入 TTFT。部分文本仅用于诊断，不能发布经验或成为游戏行动。

实验请求：`name, participants:[{agentId,initialExperienceId?,selection?}], playerCount, targetGames, wordPack, hasBlank, budgetPoints, coach`。阵容数量等于 4–10 个席位，允许重复 agentId；服务端为每个副本生成稳定 participantId。selection 与教练同形，仅覆盖本次实验副本模型，省略则使用来源档案默认值，不改写档案。目标 1–10000 局，各实验固定逐局执行，内部全局资源上限保持 3 局。兼容旧 `agentIds` 和按档案指定的 `initialExperienceIds`，但不能同时提交两种阵容；旧请求的 concurrency 被忽略，当前配置及响应不再提供该字段。内置词库 daily / idiom / acg / tech。budgetPoints 必须为正整数，100 积分折合人民币 1 元。coach 使用 selection 同形结构。每个副本可显式继承相同来源档案的一个历史经验版本，省略从空经验开始。

管理员详情追加 latestFailureMatchId；摘要追加 completedGames、reviewedGames、abortedGames（只统计训练局）。诊断返回 failure、tasks、calls，历史还原原因标记 inferred；原文缺失不推测。真人行动命中本人禁词返回 400 / OWN_WORD_FORBIDDEN 或 FORBIDDEN_WORD_ELIMINATED，details 提供 forbiddenTerms、violationCount、remainingCorrections、eliminated、actionRequestId；业务拒绝前已提交私密计数，原 requestId 重复提交返回相同反馈，不重复扣罚。当前玩家状态 extra.wordFeedback 同步反馈，旁观者不可见。即使错误响应正文丢失，客户端也可依据私密状态中匹配的 actionRequestId 确认拒绝，下一次显式修正使用新身份；没有回执的未知提交仍沿用原身份。

评测请求：`participantId, pairs, candidateExperienceId`，pairs 为 1–1000。旧 agentId 请求仅在来源档案唯一对应一个副本时兼容。只能在暂停或训练完成、未完成对局和复盘已处理后发起。初始对手、词语、座次与身份分配种子冻结，候选组只改变目标副本的经验。只有两个对照局均正常结束的配对进入胜率统计，评测不发布经验。两组分别返回 attemptedGames、failedGames 和 failureRate（失败局 / 已结束尝试）；有效行动率包含中断局已执行的行动。分身份统计提供 games、wins 和 winRate。

管理观战返回 `match, room, state?`，state 复用原公开观战投影，不占座、不更新玩家在线状态。已过期且存在归档的对局只返回房间摘要与回放标识；完整回放沿用 `GET /api/admin/replays/{archiveId}/events`。公开玩家接口不接收管理员身份代替玩家登录。

费用摘要返回 spentPoints、pendingCalls、totalCalls、actualCost。实验只在新调用准备前以累计 spentPoints 检查预算；钱包不再冻结积分，收到权威用量后按实扣款，允许负余额。未知调用的 billedPoints 为 null。达到预算后不准入新调用，已准入的在途调用继续结算，最终实扣可能超过预算。积分取整和供应商实际金额保持不同口径，结果未知只对账，不重新发送。

预算修改须为正整数且不低于保存时的累计已扣；不足返回 400 / LAB_BUDGET_BELOW_SPENT，details 提供 spentPoints、minimumPoints。调低至实扣触发预算暂停，调高不自动恢复。预算修改不重新开启 COMPLETED / STOPPED 实验。摘要提供 budgetPaused，房间状态 extra.labBudgetPaused 为 true 时不提供 legalActions，禁止真人提交动作。

状态：DRAFT → RUNNING → COMPLETED；普通 pause / stop 进入 PAUSING / STOPPING，完成当前局后生效。达到实耗预算进入 PAUSED / LAB_BUDGET_EXHAUSTED，保留当前局面、排队任务及剩余计时；在途有效结果仍可提交。调整预算后显式 resume 继续同局或待完成复盘，预算暂停时 stop 可结束保留的当前局。模型或终止性生成失败仍中断对应局；复盘失败不发布部分经验，需要明确重试。历史 ABORTED 不改写；已冻结或未知的原请求不改内容或身份。

调用恢复暂停时，活动对局仍为 PLAYING；房间 extra 返回 labCallPaused 与 labPauseReason，真人操作入口暂停，管理员恢复继续当前局。预算暂停继续使用 labBudgetPaused。诊断尝试中的 settlementState 为当前原请求结算状态；observedSettlementState（若有）仅表示原样本。业务 attempt 与 transportAttempt 分开记录，只有确认未发送才改变传输请求身份。

start / resume 在事务外先校验实际玩家和教练的思考选择、预算能力及价格；检查不执行模型推理。教练首正文窗口为 600 秒、总窗口 1200 秒、任务租约 1230 秒和 32768 总输出上限，自动格式修正最多两次。

狼人杀创建增加 `gameId:"werewolf",rules,learningMode:"SHARED_STRATEGY",initialStrategyVersionId?,randomSeed?,trainingCheckpointPoints?`。rules 为六人/九人 standard，沿用 witchRule、winCondition、hasLastWords、speechTime；首期共享策略要求所有玩家模型/思考配置一致。无 gameId 的旧请求仍创建卧底。狼人杀不能继承逐副本 initialExperienceId，使用确切共享版本并校验规则和模型兼容。观察及对局摘要均返回 gameId。

狼人杀完整对局评测用 `candidateStrategyVersionId` 替代 `candidateExperienceId`，保留 `participantId,pairs`；可附 `operationId,targetRole,randomSeed`。targetRole 是五种身份之一，服务端验证配额，配对保存相同实际身份与座次。只改变目标席经验，评测不复盘、不学习。统计包含 validPairs、全部 attemptedGames、unfinishedGames、failedGames，以及按完整配对计算的 pairedOutcomes 和保守 95% 范围。

情境运行保存在实验 JSON，实验外层保持 PAUSED，run 自身 RUNNING/PAUSED/COMPLETED/STOPPED。有 activeScenario 时 resume 只恢复该任务，不能自动开训练局；预算调高不自动继续。每个结果注明构造来源、角色、人格、初始或候选、动作与诊断；semanticReview=NOT_REVIEWED 不代表策略题正确。发布回执只能表达版本绑定及 TRIAL 状态，不能表达能力已提升。
