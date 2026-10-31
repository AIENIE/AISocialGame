# AI 质量机制

当前实现使用 V2 规则、可见观察、结构化记忆、任务诊断及人格经验。活动验收与尚未完成的目标由 [里程碑](../milestones.md) 维护，人工评阅采用 [质量评阅尺度](../ai-realism-manual-review-rubric.md)。

| 能力 | 实现 |
|---|---|
| 可见事实和输入投影 | `ObservationFactory`、`AiInformationSources`、`AiPromptProjection` |
| 决策与校验 | `AiTurnGenerator`、各玩法协议及规则实现 |
| 信念、关系、情绪与承诺 | `AiMemoryServiceV2`、`AiMemoryEntries`、`AiCommitments` |
| 回合反思 | `AiRoundReflection` |
| 任务诊断与实际响应 | `AiJobDiagnostics`、`LocalAiDebugRecorder` |
| 决策查询 | `AiDecisionTraceService`、管理端 AI 页面 |
| 人格经验与策略 | `ai_persona_memories`、实验室经验和策略发布服务 |

局内记忆保持来源、证据与承诺状态，模型提案经当前观察和规则校验后写入。跨局经验审核与策略发布分别管理；使用已发布策略的人格须先撤回发布再重置或直接审核通用经验。

失败、未发送、已发送未决与已完成响应分别记录。只有实际响应提供的用量和结算凭证可作为费用依据。局面结束、任务完成与模型质量验收分别检查。

回归复用 `AiTurnGeneratorTest`、记忆/承诺测试、`V2GameServiceIntegrationTest` 及实验室测试。真实模型测试按现有显式授权和费用上限运行。
