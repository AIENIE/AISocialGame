# 集成测试基线（持续更新）

## 1. 执行入口

- 部署入口：`sudo ./build.sh`
- 测试域名：`https://aisocialgame.seekerhut.com`
- 前端端口：`11030`
- 后端端口：`11031`

## 2. build.sh 标准链路

`build.sh` / `build_prod.sh` 通过 `build_common.sh` 共用流程，仅默认域名不同。

标准链路包含：

1. 后端 `mvn clean test package`
2. 前端 `pnpm install --frozen-lockfile && pnpm build`
3. Docker Compose 重建前后端
4. 健康检查（前端首页、后端 `/actuator/health`）
5. 自动执行全量积分迁移（`/api/admin/billing/migrate-all`）

说明：`build.sh` 不再自动执行 Playwright。

## 3. 真人验收（强制）

部署成功后必须执行 subagent + Playwright 真人验收，不可用脚本代替：

1. 谁是卧底：`1 用户 + 其余 AI`
2. 谁是卧底：`3 用户 + 其余 AI`
3. 狼人杀：`1 用户 + 其余 AI`
4. 狼人杀：`3 用户 + 其余 AI`

验收要求：

- 每场必须从建房到结算完整闭环。
- 发言与投票需基于场上信息做出可解释判断，不允许随机行为。
- 若余额不足，现场创建兑换码并完成兑换后继续。

## 4. 账号与余额策略

- 普通账号从仓库根目录 `testuser.txt` 获取。
- 管理账号默认 `admin/admin123`（可被环境变量覆盖）。
- 余额不足时流程：
  1. 管理员登录
  2. 创建兑换码
  3. 目标玩家兑换
  4. 复查余额继续对局

## 5. 报告产物（本地）

每次真人验收输出 4 篇完整报告：

- `01-undercover-1user-plus-ai.md`
- `02-undercover-3user-plus-ai.md`
- `03-werewolf-1user-plus-ai.md`
- `04-werewolf-3user-plus-ai.md`
- `index.md`

目录：`result/game-reports/<run-id>/`

报告必须包含：

- 人类玩家完整行为时间线（含发言/投票/夜晚行动）
- AI 角色发言与行为
- 系统关键日志
- 结算结果与问题处理记录

`result/` 为本地产物目录，默认不入库。

## 6. 常见失败信号

- `POST /api/auth/sso-callback` 返回 `401 Invalid token`
  - 常见根因：`APP_EXTERNAL_PAYSERVICE_JWT` 过期
  - 处置：按 pay-service 鉴权约束重签服务 JWT，再执行 `sudo ./build.sh`

- 对局流程卡住（未推进到结算）
  - 处置：按真实用户视角重试当前回合动作；若可稳定复现，先修复代码再重新部署与复测。

## 7. AI 质量闭环联调

- Mock 自动化：
  - `cd backend`
  - `mvn test -Dtest=AiDecisionServiceTest,GamePlayServiceUndercoverTest,GamePlayServiceWerewolfTest`
- 真实 ai-service 联调：
  - `cd backend`
  - `set -a && source ../env.txt && set +a`
  - `REAL_AI_INTEGRATION=1 mvn test -Dtest=AiDecisionRealIntegrationTest`
- 验收重点：
  - AI 发言/投票/夜晚行动后生成 `ai_decision_traces`。
  - 日志 metadata 只有安全摘要，不包含隐藏词语、隐藏身份或完整 Prompt。
  - `ai_persona_memories` 会沉淀 Persona 级策略和错误记忆，可通过管理端重置。

## 8. 最近一次执行记录（2026-03-04）

- 报告目录：`result/game-reports/20260304132122-subagent-rerun/`
- 4 场真人对局均完成到结算：
  - `01-undercover-1user-plus-ai.md`
  - `02-undercover-3user-plus-ai.md`
  - `03-werewolf-1user-plus-ai.md`
  - `04-werewolf-3user-plus-ai.md`
- 期间发现并修复：
  - 满房重连时 `joinRoom` 误判导致真人 `myPlayerId/mySeatNumber` 丢失，出现“轮到发言但无输入框”；
  - 构建迁移阶段 pay-service JWT 使用了错误 claim（`scope`），修正为 `scopes` 后恢复。
- 修复后复测：
  - `sudo ./build.sh` 成功，迁移 `failed=0`；
  - `pnpm test:e2e`：3 passed / 1 skipped；
  - `REAL_E2E=1 pnpm playwright test tests/real-flow.spec.ts`：1 passed。
