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
| POST | /experiments | 创建草稿 |
| POST | /experiments/{id}/control/{action} | start、resume、pause、stop |
| GET | /experiments/{id}/experiences?page=0 | 经验版本、来源及证据分页 |
| GET | /experiments/{id}/matches/{matchId}/spectate | 绑定实验的只读公开观战视图，仅管理会话 |
| GET | /experiments/{id}/matches/{matchId}/diagnostics | 绑定实验的管理员只读诊断，含失败位置、可用的失败动作和原请求账务，不返回提示词或内部推理 |
| POST | /experiments/{id}/rollback/{experienceId} | 暂停或完成状态下生成回退版本 |
| POST | /experiments/{id}/retry-review | 管理员重试失败复盘并继续 |
| POST | /experiments/{id}/evaluations | 发起冻结对手的纯 AI 成对评测 |

AI 档案请求：`name, personaId, selection{modelKey,thinkingMode,thinkingEffort}`。思考能力遵循已有模型目录；未知能力仅允许 DEFAULT。失效选择保留并报错。

实验请求：`name, participants:[{agentId,initialExperienceId?,selection?}], playerCount, targetGames, wordPack, hasBlank, budgetPoints, coach`。阵容数量等于 4–10 个席位，允许重复 agentId；服务端为每个副本生成稳定 participantId。selection 与教练同形，仅覆盖本次实验副本模型，省略则使用来源档案默认值，不改写档案。目标 1–10000 局，各实验固定逐局执行，内部全局资源上限保持 3 局。兼容旧 `agentIds` 和按档案指定的 `initialExperienceIds`，但不能同时提交两种阵容；旧请求的 concurrency 被忽略，当前配置及响应不再提供该字段。内置词库 daily / idiom / acg / tech。budgetPoints 必须为正整数，100 积分折合人民币 1 元。coach 使用 selection 同形结构。每个副本可显式继承相同来源档案的一个历史经验版本，省略从空经验开始。

管理员详情追加 latestFailureMatchId；摘要追加 completedGames、reviewedGames、abortedGames（只统计训练局）。诊断返回 failure、tasks、calls，历史还原原因标记 inferred；原文缺失不推测。真人行动命中本人禁词返回 400 / OWN_WORD_FORBIDDEN 或 FORBIDDEN_WORD_ELIMINATED，details 提供 forbiddenTerms、violationCount、remainingCorrections、eliminated；业务拒绝前已提交私密计数，原 requestId 重复提交返回相同反馈，不重复扣罚。当前玩家状态 extra.wordFeedback 同步反馈，旁观者不可见。

评测请求：`participantId, pairs, candidateExperienceId`，pairs 为 1–1000。旧 agentId 请求仅在来源档案唯一对应一个副本时兼容。只能在暂停或训练完成、未完成对局和复盘已处理后发起。初始对手、词语、座次与身份分配种子冻结，候选组只改变目标副本的经验。只有两个对照局均正常结束的配对进入胜率统计，评测不发布经验。两组分别返回 attemptedGames、failedGames 和 failureRate（失败局 / 已结束尝试）；有效行动率包含中断局已执行的行动。分身份统计提供 games、wins 和 winRate。

管理观战返回 `match, room, state?`，state 复用原公开观战投影，不占座、不更新玩家在线状态。已过期且存在归档的对局只返回房间摘要与回放标识；完整回放沿用 `GET /api/admin/replays/{archiveId}/events`。公开玩家接口不接收管理员身份代替玩家登录。

费用摘要分别返回 spentPoints、heldPoints、pendingPoints、pendingCalls、totalCalls、actualCost。pendingPoints 是尚未结算调用的预留上界，已包含在 heldPoints 内，不重复计入预算。积分取整和供应商实际金额保持不同口径。所有实验调用在原钱包预留事务内核对总上限，结果未知只对账，不重新发送。

状态：DRAFT → RUNNING → COMPLETED；pause / stop 进入 PAUSING / STOPPING，完成当前局后生效。预算、模型或终止性生成失败中断对应局并暂停；该局不学习、不计有效评测。复盘失败不发布部分经验，需要明确重试。已冻结或未知的原请求不改内容或身份。
