# 性能与稳定性加固模块

> 更新时间：2026-05-19

## 模块目标

本模块记录 v1.0 性能与稳定性整改后的运行约束，覆盖账务兑换、AI 流式请求、房间并发、分页查询、内存状态清理、Schema 策略和 request id 观测链路。

## 关键实现

### 账务兑换事务边界

- `ProjectCreditService.exchangePublicToProject` 使用 `TransactionTemplate` 显式拆分事务。
- 第一段事务只创建 `PENDING` 兑换记录、校验日限额并记录兑换前余额。
- pay-service `convertPublicToProject` 在本地数据库事务外执行，避免数据库锁等待远程 gRPC。
- 远程失败时进入新事务落 `FAILED`，保留 `retriable=true` 与 `failReason`。
- 远程成功后进入新事务锁定本地账户、写入 `EXCHANGE_IN` 流水并落 `SUCCESS`。
- 若远程已成功但本地成功事务失败，兑换记录保持 `PENDING`，后续可按 `requestId` 人工核对或补偿。

### AI 流式请求

- `AsyncExecutionConfig` 提供 `aiStreamTaskExecutor`，避免使用公共 ForkJoinPool。
- `/api/ai/chat/stream` 通过 `AiStreamConcurrencyLimiter` 控制单用户并发。
- 线程池满或用户并发超限时返回 429，避免大量 SSE 请求长期占用线程。
- 当前 AI gRPC 仍是非真实流式返回，后端按 16 字符 chunk 快速输出，不再固定 sleep。

### 房间并发与查询

- `RoomService.joinRoom/addAi` 不再使用服务级 `synchronized`。
- `RoomRepository.findByIdForUpdate` 使用房间行级悲观锁保护座位 JSON 写入。
- `Room` 增加 `seatCount` 和 `version`，`seatCount` 在持久化前同步，用于列表展示和游戏在线人数聚合。
- `GET /api/games/{gameId}/rooms` 支持分页和状态过滤，默认只返回等待中房间第一页。
- `GameService` 使用 `sumSeatCountByGameId` 聚合在线人数，避免按游戏反序列化全部房间座位。

### 运行时内存状态

- 管理员 session 在非 test 环境使用 Redis TTL 存储，支持多实例共享和自动过期。
- test/无 Redis 构造路径仍可使用内存 Map，并通过定时任务清理过期 token。
- `ChatRateLimiter` 定期清理长时间未发言玩家。
- `PlayerConnectionService` 定期清理无 session 且长期未活跃的连接状态。

### Schema 与部署

- 默认 `spring.jpa.hibernate.ddl-auto=validate`，部署环境不再自动改表。
- 如需本地临时调试自动改表，手动设置 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`；部署脚本默认仍保持 `validate`。
- 本轮 SQL 迁移：`backend/sql/20260519_performance_stability.sql`。
- 全量结构：`backend/sql/schema.sql`。

### Request ID

- `RequestIdFilter` 透传或生成 `X-Request-Id`。
- request id 写入响应头、错误响应体和 MDC。
- `GlobalExceptionHandler` 记录未捕获异常时附带 request id，便于线上日志定位。

## 配置项

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `app.ai.stream.core-pool-size` | 4 | AI SSE 核心线程数 |
| `app.ai.stream.max-pool-size` | 16 | AI SSE 最大线程数 |
| `app.ai.stream.queue-capacity` | 64 | AI SSE 排队容量 |
| `app.ai.stream.max-concurrent-per-user` | 2 | 单用户 SSE 并发上限 |
| `app.websocket.rate-limit-cleanup-interval-ms` | 300000 | 聊天限流清理周期 |
| `app.websocket.connection-cleanup-interval-ms` | 300000 | 连接状态清理周期 |
| `app.admin.session-cleanup-interval-ms` | 300000 | 内存管理员 session 清理周期 |

## 验证命令

```bash
cd backend && mvn test
cd frontend && pnpm build
rg -n "CompletableFuture.runAsync|Thread.sleep|synchronized .*joinRoom|synchronized .*addAi" backend/src/main/java
```
