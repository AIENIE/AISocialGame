# AI 决策模块

`AiTurnCoordinator` 调用 `ObservationFactory` 和 `AiTurnGenerator`，按当前玩法、角色、人格、可见事件、记忆与合法动作生成输入。提示资源位于 `resources/prompts/`，模型选择由 `AiModelConfigService` 管理。

生成经过发送前预算、模型调用、结构解析和玩法校验。各玩法沿用现有次数、时间及预算上限；格式或规则纠错产生的尝试保留独立请求和诊断。原请求结果未知时仅查询原请求，不重复生成。

`V2GameService` 提交动作前重新核查当前机会、截止时间及治理控制。任务诊断记录实际响应、用量、失败阶段与原因，失败不能作为成功模型行动展示。

`AiMemoryServiceV2` 管理局内结构化记忆；`AiDecisionTraceService` 提供管理端决策查询。跨局通用经验与实验室策略按现有审核及发布规则使用。

主要回归为 `AiTurnGeneratorTest`、`V2GameServiceIntegrationTest` 和各玩法协议/规则测试。实际模型质量与未完成验收见 [里程碑](../milestones.md)。

## 谁是卧底发言对照

候选 `undercover-v1.3` 提示 AI 在普通描述和最高票并列时选一个尚未提过的相关特征。首位白板提供便于比较的试探性描述，已有线索的白板沿着公开描述继续试探。平票补充沿用合法的跳过选项。当前结果见[里程碑](../milestones.md#谁是卧底发言2026-11-16首答对照及混合局)。

对照使用白板首位、白板末位、跨轮补充、平票补充四种场景，每种使用福尔摩斯和小丑。基线为 `develop@7276aec` 的阶段提示，候选使用当前规则。每对固定观察和模型设置。`UndercoverSpeechComparisonTest` 验证输入差异及卧底专用调用入口的累计计数。

通过后端 L2 后，运行 `scripts/windows/Test-UndercoverSpeech.ps1 -EvidenceDirectory <仓库外目录>`，导出近期普通局回放摘要和当前模型报价。实际对照由同一脚本读取已确认的 `-ApprovalFile`，字段为 `quoteSha256`、`approvalReference`、`expiresAt`、`comparisonCallLimit`（16）和 `comparisonStopPoints`。账本逐笔记录原请求。用量未结算或结果待查时，保留已收集材料并结束本批发送。

用 `-ModelKey` 和 `-ThinkingEffort` 固定本批对照模型及思考档位。报价与执行使用同一组参数和同一份冻结输入。

逐对评阅首答是否符合场景要求、动作是否合法、发言是否越过玩家视角，并核对等待时间和实扣。候选至少通过八项中的六项，同时至少比基线多通过两项，且每项均满足规则和视角要求。达到对照条件后，在正常 local 入口分别验收一真人三 AI 和三真人一 AI 的四席对局。
