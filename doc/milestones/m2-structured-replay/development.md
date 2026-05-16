# M2 结构化事件与回放/质检底座开发记录

> 完成日期：2026-05-16
> 对应提交：`e58d330 feat: add structured replay archives`

## 目标

M2 的目标是把当前对局文本日志扩展为服务端结构化事件流，为回放、观战、AI 质量评估、赛后战报、争议审计和未来训练数据沉淀提供统一底座。

## 代码改动

- 后端新增结构化事件模型：
  - `GameEvent`
  - `GameEventVisibility`
  - `GameArchive`
  - `GameEventRepository`
  - `GameArchiveRepository`
- 后端新增回放服务：
  - `GameEventRecorder`
  - `ReplayArchiveService`
- 后端新增回放 API：
  - `ReplayController`
  - `ReplayArchiveView`
  - `ReplayEventView`
  - `ReplayDetailResponse`
- 新增数据库结构：
  - `backend/sql/game_replays.sql`
  - `backend/sql/schema.sql` 引入回放结构。
- 更新游戏流程：
  - 开局记录 `GAME_START`。
  - 身份、词语、发言、投票、夜晚行动和结算写入结构化事件。
  - 结算时生成 `game_archives`。
  - 保留原 `GameState.logs`，继续服务房间页。
- 更新前端回放：
  - `/replays` 优先读取服务端归档。
  - `/replay/:archiveId` 支持服务端事件播放。
  - 支持 `PUBLIC`、`PLAYER`、`GOD` 视角过滤。
  - 服务端不可用时保留本地回放降级。

## 文档改动

- 总路线图实现摘要：[../../milestones.md](../../milestones.md)
- 结构化事件与回放模块：[../../modules/replay-event-module.md](../../modules/replay-event-module.md)
- 回放 API：[../../api/ReplayController.md](../../api/ReplayController.md)
- 模块索引更新：[../../modules/README.md](../../modules/README.md)
- 项目结构更新：[../../structure.md](../../structure.md)
- 集成测试记录：[../../test/integratedTest.md](../../test/integratedTest.md)

## 测试与验收

- 后端单元测试：`cd backend && mvn test`。
- 前端构建：`cd frontend && pnpm build`。
- Playwright 可重复验收：
  - `PLAYWRIGHT_BASE_URL=http://127.0.0.1:11030 pnpm test:e2e`
  - `REAL_ACCEPTANCE=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:11030 pnpm test:acceptance`
- 浏览器一次性验收：
  - 打开 `/replays` 查看服务端归档列表。
  - 进入 `/replay/:archiveId` 验证回放播放器、视角切换、单步播放、时间线和事件列表。

## 后续影响

- M1 的 AI 质检可以通过事件序号定位具体发言、投票和夜晚行动上下文。
- M5 可基于归档生成赛后战报、社区分享和玩家表现摘要。
- M6 可基于结构化事件做审计、异常行为追踪和模型质量统计。
