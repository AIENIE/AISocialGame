# AI 决策模块

`AiTurnCoordinator` 调用 `ObservationFactory` 和 `AiTurnGenerator`，按当前玩法、角色、人格、可见事件、记忆与合法动作生成输入。提示资源位于 `resources/prompts/`，模型选择由 `AiModelConfigService` 管理。

生成经过发送前预算、模型调用、结构解析和玩法校验。各玩法沿用现有次数、时间及预算上限；格式或规则纠错产生的尝试保留独立请求和诊断。原请求结果未知时仅查询原请求，不重复生成。

`V2GameService` 提交动作前重新核查当前机会、截止时间及治理控制。任务诊断记录实际响应、用量、失败阶段与原因，失败不能作为成功模型行动展示。

`AiMemoryServiceV2` 管理局内结构化记忆；`AiDecisionTraceService` 提供管理端决策查询。跨局通用经验与实验室策略按现有审核及发布规则使用。

主要回归为 `AiTurnGeneratorTest`、`V2GameServiceIntegrationTest` 和各玩法协议/规则测试。实际模型质量与未完成验收见 [里程碑](../milestones.md)。

## 谁是卧底发言对照

`undercover-v1.4` 的首轮普通描述要求有词玩家给出一条真实、也能适用于相近候选词的线索，并结合此前公开发言判断会不会让听众锁定自己的词。真人席验收脚本按自己的词从预审的日常开场线索里选择。第二轮起和最高票并列时，补充描述优先给出尚未提过的相关特征。白板沿着公开线索试探，平票候选人可以选择跳过补充。新版混合局结果见[首轮线索复测](../milestones.md#谁是卧底首轮线索复测2026-11-16)。

首答对照使用白板首位、白板末位、跨轮补充、平票补充四种场景，每种使用福尔摩斯和小丑。基线为 `develop@7276aec` 的阶段提示，候选为 `undercover-v1.3` 的阶段提示。每对固定观察和模型设置。`UndercoverSpeechComparisonTest` 校验对照输入摘要及卧底专用调用入口的累计计数。对照和原两局见[里程碑](../milestones.md#谁是卧底发言2026-11-16首答对照及混合局)。

通过后端 L2 后，运行 `scripts/windows/Test-UndercoverSpeech.ps1 -EvidenceDirectory <仓库外目录>`，导出近期普通局回放摘要和当前模型报价。实际对照由同一脚本读取已确认的 `-ApprovalFile`，字段为 `quoteSha256`、`approvalReference`、`expiresAt`、`comparisonCallLimit`（16）和 `comparisonStopPoints`。账本逐笔记录原请求。用量未结算或结果待查时，保留已收集材料并结束本批发送。

用 `-ModelKey` 和 `-ThinkingEffort` 固定本批对照模型及思考档位。报价与执行使用同一组参数和同一份冻结输入。

逐对评阅首答是否符合场景要求、动作是否合法、发言是否越过玩家视角，并核对等待时间和实扣。候选至少通过八项中的六项，同时至少比基线多通过两项，且每项均满足规则和视角要求。达到对照条件后，在正常 local 入口分别验收一真人三 AI 和三真人一 AI 的四席对局。
