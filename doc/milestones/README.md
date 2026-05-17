# 已完成里程碑归档

> 更新时间：2026-05-17

本目录按里程碑保存阶段性开发记录。每个里程碑目录至少包含 `development.md`，用于记录本阶段实际完成的代码、文档、测试和后续风险。

## 当前归档

| 里程碑 | 状态 | 记录目录 | 说明 |
|---|---|---|---|
| M1 | 已完成 | [m1-ai-quality-loop/](m1-ai-quality-loop/) | AI 拟人质量闭环、决策 trace、Persona 记忆、管理端质检 |
| M2 | 已完成 | [m2-structured-replay/](m2-structured-replay/) | 结构化事件流、服务端回放归档、视角过滤和回放验收 |
| M3 | 已完成 | [m3-game-engine-plugin-architecture/](m3-game-engine-plugin-architecture/) | GameEngine 注册表、统一 action、现有玩法插件化入口 |

## 后续规则

- 新里程碑开始前，先在总路线图 [../milestones.md](../milestones.md) 确认优先级和范围。
- 新里程碑完成后，新增 `m<序号>-<主题>/development.md`。
- 里程碑目录记录“本阶段做了什么”，模块/API/测试目录记录“系统现在如何工作”。
