# 本地开箱即用数据与验收模块

## 目标

让本地环境启动后具备足够的演示内容，能够直接浏览社区、排行榜、房间列表、AI 质检与 Persona 记忆；同时提供可复用 Playwright 验收脚本，覆盖真实 AI 社交游戏闭环。

## 本地 Demo Seed

- 入口：`DemoSeedService`
- 开关：`app.demo-seed.enabled`
- 本地需要演示数据时，显式设置 `APP_DEMO_SEED_ENABLED=true`
- 部署默认：`application.yml` 默认 `false`

Seed 采用稳定 ID 和存在性检查，重复启动不会重复插入。内容包括：

- 社区帖子：AI 质检、谁是卧底、狼人杀。
- 等待房间：谁是卧底、狼人杀各 1 个。
- 排行榜：总榜与玩法榜样例。
- 兑换码：本地永久积分与临时积分样例。
- AI 质量数据：示例决策 trace 与 Persona 跨局记忆。

## 管理端 AI 质检

`/admin/ai` 支持：

- 查看可用模型和测试 AI 网关。
- 按玩法、Persona、质量标记查询 AI 决策 trace。
- 查看 trace 的动作、阶段、角色、置信度、兜底状态、质量摘要、信念快照和记忆快照。
- 查看与重置 Persona 记忆。

管理端只展示安全摘要，不展示完整 Prompt、隐藏词、隐藏身份或内部推理。

## 验收脚本

可复用脚本：`frontend/tests/acceptance-real.spec.ts`

流程：

1. 浏览首页、社区、排行榜。
2. 写入一条测试社区帖。
3. 创建并驱动 4 场真实对局到结算：
   - 谁是卧底：1 真人 + AI。
   - 谁是卧底：3 真人 + AI。
   - 狼人杀：1 真人 + AI。
   - 狼人杀：3 真人 + AI。
4. 打开房间页面确认结算与日志。
5. 登录管理端并确认 AI trace 数据可见。

本地域名在当前浏览器环境不可解析时，使用 `PLAYWRIGHT_HOST_RESOLVER_RULES` 为 Chromium 指定域名映射，无需修改系统 hosts。
