# M1 AI 拟人质量闭环开发记录

> 完成日期：2026-05-16
> 对应提交：`c2b673d feat: add AI quality loop`

## 目标

M1 的目标是让狼人杀和谁是卧底中的 AI 从“能行动”升级为“能基于局势、人设、历史互动和合法性约束做出稳定行动”。本阶段坚持 AI 身份明示，不让 AI 冒充真人。

## 代码改动

- 后端新增 AI 质量闭环能力：
  - AI 信念快照。
  - 局内短期记忆。
  - Persona 跨局记忆。
  - 决策 trace。
  - 质量 flags。
  - 面向玩家日志的安全 metadata 摘要。
- 新增管理端 AI 质检接口：
  - AI 决策 trace 查询。
  - Persona 记忆查询。
  - Persona 记忆重置。
- 更新游戏流程：
  - AI 发言、投票、狼人杀夜晚行动后写入质量记录。
  - 玩家端继续只展示公开行动、AI 标识和安全摘要，不展示完整 Prompt、隐藏词语、隐藏身份或内部推理链。

## 文档改动

- 总路线图实现摘要：[../../milestones.md](../../milestones.md)
- AI 质量闭环模块：[../../modules/ai-quality-loop-module.md](../../modules/ai-quality-loop-module.md)
- AI 决策模块更新：[../../modules/ai-decision-module.md](../../modules/ai-decision-module.md)
- 游戏流程模块更新：[../../modules/gameplay-module.md](../../modules/gameplay-module.md)
- 管理端 AI API：[../../api/AdminAiController.md](../../api/AdminAiController.md)
- 项目结构更新：[../../structure.md](../../structure.md)
- 集成测试记录：[../../test/integratedTest.md](../../test/integratedTest.md)

## 测试与验收

- 覆盖谁是卧底与狼人杀 AI 自动发言、投票和夜晚行动。
- 验证 AI 决策记录中包含信念、记忆、质量 flags、兜底情况和耗时信息。
- 验证管理端能查询 AI trace 和 Persona 记忆。
- 验证玩家端不会暴露隐藏词语、隐藏身份、完整 Prompt 或内部推理链。

## 后续影响

- M2 结构化事件流可进一步把 AI trace 与具体对局事件关联，提升复盘和质检定位效率。
- M6 AI 运营后台可复用本阶段沉淀的 trace、Persona 记忆和质量指标。
