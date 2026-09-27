# M5 新增海龟汤玩法开发记录

> 更新时间：2026-06-06
> 状态：已完成第一版端到端闭环。

## 阶段目标

将海龟汤作为首个新增玩法落地，验证平台可以支持合作式语言推理游戏，并验证 M3 的 GameEngine 注册机制、M2 的事件回放底座和 M4 的安全审核链路能被新玩法复用。

## 本次实现范围

- 新增 `turtle_soup` GameEngine，玩法状态从 `COMING_SOON` 切换为 `ACTIVE`。
- 增加内置题库、规则主持、问题上限、线索解锁、最终解答判定和结算归档。
- 统一 `/action` 扩展 `ASK_QUESTION` 与 `SUBMIT_SOLUTION`。
- 前端新增海龟汤房间页，展示汤面、问答历史、已确认线索、提问、最终解答和结算汤底。
- 真实验收脚本增加海龟汤 1 真人 + AI 玩家闭环，并校验回放事件。

## 安全边界

- 结算前不向玩家返回汤底。
- 海龟汤解答允许出现“汤底是”，但其他玩法的隐藏信息泄露规则不放宽。
- AI 玩家追问只作为辅助，不参与最终判定；规则主持保证流程可重复验收。

## 测试与验收

- 后端新增 `GamePlayServiceTurtleSoupTest` 覆盖玩法元数据、开局、提问、线索、正确解答、结算和归档。
- 后端更新 `AiSafetyServiceTest` 覆盖海龟汤解答语境和其他玩法隐藏信息保护。
- 前端 `pnpm build` 验证新增房间页可构建。
- Playwright 真实验收脚本 `frontend/tests/acceptance-real.spec.ts` 已纳入海龟汤场景，并将真实链路超时窗口调整为 15 分钟，以覆盖五场对局、回放和管理端质检的实测耗时。

### 2026-06-06 实际验收记录

- `backend mvn clean test`：通过，57 个测试执行，0 failure，0 error，1 skipped。
- `frontend pnpm build`：通过，仅保留既有 chunk size / Browserslist 警告。
- `sudo ./build.sh`：通过；部署后由授权运维单独执行迁移，结果为 `scanned=28, success=28, failed=0`。
- 健康检查：`http://127.0.0.1:11031/actuator/health` 与 `https://localsocialgame.testhut.top/actuator/health` 均返回 `UP`。
- 海龟汤专项真实验收：使用真实 SSO token 访问 `localsocialgame.testhut.top`，完成创建房间、AI 入座、开局、提问、线索解锁、正确解答、结算、回放事件校验，并用系统 Chromium 打开房间页确认 `SETTLEMENT` 与“汤底揭示”可见。
- 全量真实验收脚本：`REAL_ACCEPTANCE=1 PLAYWRIGHT_BASE_URL=https://localsocialgame.testhut.top pnpm test:acceptance` 通过，覆盖首页、社区、排行榜、谁是卧底两场、狼人杀两场、海龟汤一场、回放 UI 与 Admin AI 质检；实测耗时 10.3 分钟。

## 后续影响

- M6/M7 仍为后续路线图，不属于本轮收口范围。
- 若后续需要运营维护题库，可将当前内置题库迁移到数据库或 Admin 配置页。


## 2026-09-22 原始交付项收尾

状态：本轮代码与离线门禁通过，最终运行及语义验收未完成；历史记录保留。

- 公开题目目录增加 random；只在开局解析一次并持久化具体题目与版本，重试、恢复和重复提交不重抽。
- 保留既有异步主持、事实包、失败/重复裁决预算和最终解答机会，不另建主持流程。主持固定夹具覆盖问题、澄清/未知/错误前提、正确/错误解答及重复问题。
- 真实六局脚本在Closure模式要求两种海龟汤配置成功 SOLVED，不能用主动揭底或历史重放通过；答案由仓库外预先冻结的验收文件提供。
- 随机稳定性与主持/预算/信息边界L2通过；未运行真实模型，成功解题与自然合作没有本轮真实证据。脚本提交答案证明流程，不等于独立真人推理。

统一门禁：标准前后端 L2 后端 511 项（506 通过、5 项外部跳过），前端 58 项，零失败/错误；类型检查、构建、独立 Java 指标及 Python 6 项指标测试通过。实际 MySQL 完整迁移仍未验证；本轮未启用 caller、调用真实模型、应用共享迁移、提交、推送或部署。

详细实现、逐项测试、语义局限与仓库外证据见 [收尾报告](../../test/m1-m5-closure-20260922.md)，后续操作按 [执行清单](../../operations/m1-m5-live-execution-checklist.md)。总体 L4 未通过。
