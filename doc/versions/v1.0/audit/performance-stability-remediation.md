# AISocialGame v1.0 性能与稳定性整改记录

> 整改日期：2026-05-19

## 整改摘要

本轮按 `performance-stability-audit.md` 处理高/中风险项，重点改造账务事务边界、AI SSE 线程模型、房间并发锁粒度、房间列表分页、运行时内存状态清理、DDL 策略和 request id 观测链路。

## 已处理问题

| 原风险 | 处理方式 | 关键文件 |
|---|---|---|
| 事务内执行 pay-service gRPC 兑换 | `TransactionTemplate` 拆分 PENDING、远程调用、SUCCESS/FAILED 三段 | `ProjectCreditService` |
| SSE 使用公共线程池和固定 sleep | 新增受控 AI stream executor、用户并发限制、移除 sleep | `AsyncExecutionConfig`、`AiController`、`AiStreamConcurrencyLimiter` |
| 房间写操作服务级串行 | 改为房间行级悲观锁，增加 `version` 与 `seatCount` | `RoomService`、`RoomRepository`、`Room` |
| 游戏列表 N+1 查询 | 使用 `sumSeatCountByGameId` 聚合在线人数 | `GameService`、`RoomRepository` |
| 房间列表无分页 | 返回 `PagedResponse<RoomResponse>`，支持 `page/size/status` | `RoomController`、前端 `roomApi` |
| 管理员 session 内存 Map 不清理 | 非 test 使用 Redis TTL，内存路径定期清理 | `AdminAuthService` |
| 聊天限流/连接状态 Map 不清理 | 增加定时清理任务 | `ChatRateLimiter`、`PlayerConnectionService` |
| 默认 `ddl-auto:update` | 应用和 compose 默认改为 `validate`，本地直启保留 `update` | `application.yml`、`docker-compose.yml`、`build.sh` |
| 错误定位缺 request id | 增加 `X-Request-Id` 过滤器、错误体和日志 MDC | `RequestIdFilter`、`GlobalExceptionHandler` |

## 接口变化

- `GET /api/games/{gameId}/rooms`
  - 原返回：`RoomResponse[]`
  - 新返回：`PagedResponse<RoomResponse>`
  - 新查询参数：`page`、`size`、`status`
  - 默认：`page=1`、`size=30`、`status=WAITING`
- `RoomResponse` 新增 `seatCount`，用于列表和在线人数展示。

## 数据库变更

新增迁移脚本：

```text
backend/sql/20260519_performance_stability.sql
```

变更内容：

- `rooms.seat_count`
- `rooms.version`
- `idx_rooms_game_status_created`

部署到测试/正式环境前，需要先执行迁移脚本，再以 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 启动后端。

## 验证结果

```bash
cd backend && mvn test
```

结果：49 tests，0 failures，0 errors，1 skipped。

```bash
cd frontend && pnpm build
```

结果：构建成功。

## 后续运营事项

- 对仍处于 `PENDING` 的兑换记录建立后台核对/补偿视图。
- 若 AI 服务未来提供真实流式 gRPC，`/chat/stream` 应改为直接转发上游 chunk。
- 测试/正式数据库执行 SQL 迁移前应备份 `rooms` 表。
