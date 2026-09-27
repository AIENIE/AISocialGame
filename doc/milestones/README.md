# 里程碑阶段记录

> 更新时间：2026-09-22

本轮追加：[完整验证](../test/ai-conversation-validation-20260922.md)：工程门禁通过；授权后的 12 项真实先导未达到质量门槛，后续 84 项停止，原账本 91，caller 已 OFFLINE。正常输出仍有答后强加分析、口头让步后继续长篇的问题；协议示例修复另行冻结，L4 未通过。

最新追加：[正常交流优化](../test/ai-conversation-20260922.md)通过离线工程门禁：交流目的与柔性篇幅提示、十二情境四人格共 48 份输入。真实效果未验证，降级优化下一期；M1-R5/L4 状态不变。

本目录按里程碑保存阶段性记录。每个里程碑目录至少包含 `development.md`，用于记录本阶段实际完成的代码、文档、测试和后续风险；尚未进入开发的里程碑也可以先保存规划记录。

## 当前记录

| 里程碑 | 状态 | 记录目录 | 说明 |
|---|---|---|---|
| M1 | 历史基础已交付；增强部分完成 | [m1-ai-quality-loop/](m1-ai-quality-loop/) | M1-R1～R4 已完成并通过 L2（R3 含前后端）；R5 评测工程完成，真实验收未完成；L4 未通过 |
| M2 | 历史已交付；当前收尾 L2 通过、最终验收待完成 | [m2-structured-replay/](m2-structured-replay/) | 结构化事件流、服务端回放归档、视角过滤和回放验收 |
| M3 | 历史已交付；当前收尾 L2 通过、最终验收待完成 | [m3-game-engine-plugin-architecture/](m3-game-engine-plugin-architecture/) | GameEngine 注册表、统一 action、现有玩法插件化入口 |
| M4 | 历史已交付；当前收尾 L2 通过、最终验收待完成 | [m4-ai-safety-admin-ops/](m4-ai-safety-admin-ops/) | AI 安全治理、风险事件、分级处置、Admin 应急运营 |
| M5 | 历史已交付；当前收尾 L2 通过、最终验收待完成 | [m5-turtle-soup/](m5-turtle-soup/) | 海龟汤规则主持、AI 追问、问答线索、结算揭底和回放归档 |

本轮统一状态、限制及证据见 [收尾报告](../test/m1-m5-closure-20260922.md)，真实执行前按 [清单](../operations/m1-m5-live-execution-checklist.md) 核对。M1～M5 均未用本轮 mock 结果标记 L4 通过。

## 后续规则

最新追加：[验收入口修复与只读预检](../test/readonly-preflight-20260922.md)已交付。矩阵/运行配置数据库端口冲突，持久预算及 caller 当前状态未知；申请草案未授权。已完成项与未知项分别记录，最终真实验收及 L4 保持未通过。

最新状态：2026-09-22 [环境门禁收尾](../test/environment-gates-20260922.md)完成本机 MySQL 8.0.45 隔离验证及 Node 22.23.2 工具链统一。下面的此前待办状态作为历史记录保留；共享环境应用、预算授权与真实评测/六局仍待完成，L4 未通过。

2026-09-22 追加：[验收判定与连续评测可靠性修复](../test/acceptance-reliability-20260922.md)已通过离线门禁。新版证据格式为 schema 2 / closure-v2，旧证据保留但不计入当前覆盖。MySQL、Node 环境统一和真实质量验收仍待执行，L4 未通过。

- 新里程碑开始前，先在总路线图 [../milestones.md](../milestones.md) 确认优先级和范围。
- 新里程碑完成后，新增 `m<序号>-<主题>/development.md`。
- 里程碑目录记录“本阶段做了什么”，模块/API/测试目录记录“系统现在如何工作”。
