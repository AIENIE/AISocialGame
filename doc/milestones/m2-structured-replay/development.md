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


## 2026-09-22 原始交付项收尾

状态：本轮代码与离线门禁通过，最终运行及语义验收未完成；历史记录保留。

- 新旧回放共用授权：本人 PLAYER，公开 PUBLIC，普通请求拒绝 GOD/冒用身份；管理员独立认证入口读取完整回放。
- /my 强制认证并按本人参与过滤；时间/玩家筛选先于分页，时间与 ID 稳定排序。列表和摘要投影玩家公开字段，前端401/403禁止缓存降级。
- 新 trace 写 quality.instanceId/eventIds，生成事件关联 trace ID；归档质量只按本局关联计算，无可靠归属的旧 trace 单列未知。
- 回放阶段跳转、事件定位和管理员从质检跳转受保护的回放已接通。内部依据、记忆和诊断不进入玩家响应。
- 旧新 ACL、同房间多局、分页和揭示时间轴回归通过；当前六局完整归档/回放实际验收未执行。

统一门禁：标准前后端 L2 后端 511 项（506 通过、5 项外部跳过），前端 58 项，零失败/错误；类型检查、构建、独立 Java 指标及 Python 6 项指标测试通过。实际 MySQL 完整迁移仍未验证；本轮未启用 caller、调用真实模型、应用共享迁移、提交、推送或部署。

详细实现、逐项测试、语义局限与仓库外证据见 [收尾报告](../../test/m1-m5-closure-20260922.md)，后续操作按 [执行清单](../../operations/m1-m5-live-execution-checklist.md)。总体 L4 未通过。

### 2026-09-22 运行证据关联修复

受管构建将非秘密 buildId/sourceFingerprint 写入新任务诊断和成功 trace，旧任务保持未知；未增加公共接口、表或玩家字段。六局采集从实际公开响应保存 archiveId，运行指标按 instanceId 查询任务并按 jobId/instanceId 核对 trace。报告绑定六局文件 SHA-256、批次和完整对局集合，拒绝同房混局、孤立/重复关联和缺失成功 trace。

构建 JAR 哈希另存并核对源码冻结摘要；摘要覆盖应用、知识、人格、夹具和判定脚本。后端完整 L2 516 项（511 通过、5 外部跳过）、独立 Java mock JDBC 指标及 Python 14 项通过。六局实际归档验收尚未执行，L4 未通过；见[本批报告](../../test/acceptance-reliability-20260922.md)。
