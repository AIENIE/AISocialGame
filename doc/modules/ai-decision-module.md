# AI 决策模块

`AiTurnCoordinator` 调用 `ObservationFactory` 和 `AiTurnGenerator`，按当前玩法、角色、人格、可见事件、记忆与合法动作生成输入。提示资源位于 `resources/prompts/`，模型选择由 `AiModelConfigService` 管理。

生成经过发送前预算、模型调用、结构解析和玩法校验。各玩法沿用现有次数、时间及预算上限；格式或规则纠错产生的尝试保留独立请求和诊断。原请求结果未知时仅查询原请求，不重复生成。

`V2GameService` 提交动作前重新核查当前机会、截止时间及治理控制。任务诊断记录实际响应、用量、失败阶段与原因，失败不能作为成功模型行动展示。

`AiMemoryServiceV2` 管理局内结构化记忆；`AiDecisionTraceService` 提供管理端决策查询。跨局通用经验与实验室策略按现有审核及发布规则使用。

主要回归为 `AiTurnGeneratorTest`、`V2GameServiceIntegrationTest` 和各玩法协议/规则测试。实际模型质量与未完成验收见 [里程碑](../milestones.md)。
