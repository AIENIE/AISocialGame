# AISocialGame 文档索引

> 更新时间：2026-06-06

本目录同时保存项目长期路线图、里程碑阶段记录、模块架构、接口说明和测试验收记录。后续开发应优先从本文件进入，避免里程碑记录散落在单个模块文档中。

## 里程碑文档

- 总路线图：[milestones.md](milestones.md)
- 里程碑阶段记录：[milestones/README.md](milestones/README.md)
- M1 AI 拟人质量闭环：[milestones/m1-ai-quality-loop/development.md](milestones/m1-ai-quality-loop/development.md)
- M2 结构化事件与回放/质检底座：[milestones/m2-structured-replay/development.md](milestones/m2-structured-replay/development.md)
- M3 GameEngine 插件化架构：[milestones/m3-game-engine-plugin-architecture/development.md](milestones/m3-game-engine-plugin-architecture/development.md)
- M4 AI 安全治理与 Admin 应急运营：[milestones/m4-ai-safety-admin-ops/development.md](milestones/m4-ai-safety-admin-ops/development.md)
- M5 新增海龟汤玩法：[milestones/m5-turtle-soup/development.md](milestones/m5-turtle-soup/development.md)

## 架构与模块

- 项目结构：[structure.md](structure.md)
- 模块索引：[modules/README.md](modules/README.md)
- GameEngine 插件化模块：[modules/game-engine-module.md](modules/game-engine-module.md)
- 海龟汤玩法模块：[modules/turtle-soup-module.md](modules/turtle-soup-module.md)
- AI 安全治理与 Admin 应急运营模块：[modules/ai-safety-admin-ops-module.md](modules/ai-safety-admin-ops-module.md)
- 安全加固模块：[modules/security-hardening-module.md](modules/security-hardening-module.md)
- 性能与稳定性加固模块：[modules/performance-stability-module.md](modules/performance-stability-module.md)
- v1.0 可维护性整改记录：[versions/v1.0/audit/maintainability-remediation.md](versions/v1.0/audit/maintainability-remediation.md)
- API 文档：[api/](api/)
- 测试与验收：[test/](test/)
- 问题与试错记录：[issues.md](issues.md)、[trialAndError/](trialAndError/)

## 维护规则

- 每个里程碑完成后，在 `doc/milestones/<milestone-id>/development.md` 记录本轮代码改动、文档改动、测试验收和后续遗留项。
- `doc/milestones.md` 只维护长期路线图、阶段目标和实现摘要，详细修改记录放入对应里程碑目录。
- 通用模块设计继续放在 `doc/modules/`，接口细节继续放在 `doc/api/`，测试细节继续放在 `doc/test/`。
