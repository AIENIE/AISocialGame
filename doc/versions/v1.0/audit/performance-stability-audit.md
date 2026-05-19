# AISocialGame v1.0 代码性能与稳定性审计

- 审计日期：2026-05-19
- 审计范围：后端热点路径、数据库访问、事务、WebSocket/SSE、前端请求、构建部署与测试稳定性
- 风险等级：严重 / 高 / 中 / 低 / 建议

## 总体结论

项目的核心稳定性风险集中在三处：账务事务中夹杂远程 gRPC 调用，游戏房间和对局状态使用较粗粒度同步与大类承载，实时连接相关内存结构没有清理策略。当前用户规模较小时能工作，但一旦房间数、AI 调用和管理员操作增长，容易出现锁等待、线程池耗尽、内存增长和查询放大。

建议优先拆分账务远程调用事务边界、限制 AI/SSE 并发、收敛房间查询与实时状态内存生命周期。

## 风险清单

| 等级 | 风险 | 证据 | 影响 |
| --- | --- | --- | --- |
| 高 | 事务内执行远程 gRPC 兑换 | `backend/src/main/java/com/aisocialgame/service/ProjectCreditService.java:445-525` | 数据库锁持有期间等待远程服务，可能放大锁等待、失败重试和部分状态不一致 |
| 高 | SSE 使用公共 `CompletableFuture` 线程池并 `sleep` 分块 | `backend/src/main/java/com/aisocialgame/controller/AiController.java:54-77` | 高并发流式请求可能占满公共线程池，影响其他异步任务 |
| 高 | 房间加入和添加 AI 使用服务级 `synchronized` | `backend/src/main/java/com/aisocialgame/service/RoomService.java:70-118` | 所有房间串行化，热点房间会阻塞无关房间 |
| 中 | 游戏列表按游戏逐个查询房间 | `backend/src/main/java/com/aisocialgame/service/GameService.java:27-39` | 游戏数增加后形成 N+1 查询，房间多时响应变慢 |
| 中 | 房间列表无分页 | `backend/src/main/java/com/aisocialgame/repository/RoomRepository.java:10-11`、`backend/src/main/java/com/aisocialgame/service/RoomService.java:60-63`、`backend/src/main/java/com/aisocialgame/controller/RoomController.java:32-35` | 老房间累积后大厅接口响应体和查询时间持续增长 |
| 中 | 管理员 session 存在内存 Map 中且不清理过期项 | `backend/src/main/java/com/aisocialgame/service/AdminAuthService.java:16-47` | 长期运行后过期 session 保留；多实例部署下管理登录不共享 |
| 中 | 聊天限流与连接状态 Map 无后台清理 | `backend/src/main/java/com/aisocialgame/websocket/ChatRateLimiter.java:12-27`、`backend/src/main/java/com/aisocialgame/websocket/PlayerConnectionService.java:13-97` | 玩家 ID 积累后内存持续增长 |
| 中 | `ddl-auto:update` 作为默认值 | `backend/src/main/resources/application.yml:13`、`docker-compose.yml:11` | 启动时自动变更 schema，生产稳定性和回滚风险较高 |
| 中 | AI 请求输入缺少大小限制 | `backend/src/main/java/com/aisocialgame/dto/AiChatRequest.java:8-13`、`backend/src/main/java/com/aisocialgame/service/AiProxyService.java:53-97` | 大消息列表或长文本可能造成高成本调用、长延迟和内存压力 |
| 低 | `GlobalExceptionHandler` 无统一 request id 与日志落点 | `GlobalExceptionHandler.java:55-60` | 线上错误排查依赖客户端反馈，定位成本高 |

## 重点发现

### 1. 账务兑换事务边界过重

`exchangePublicToProject` 标注 `@Transactional`，在同一事务中创建本地交易、查询和锁定账户、调用 `billingGrpcClient.convertPublicToProject`，再更新本地账户和流水。远程调用慢或失败时，本地事务和悲观锁会被延长。

建议：

- 将远程扣减和本地入账拆成状态机：先保存 PENDING，再提交事务；事务外调用 pay-service；最后用新事务落 SUCCESS/FAILED。
- 对 `requestId` 建唯一索引，并用数据库唯一约束承担幂等，而不是单纯先查后写。
- 为 FAILED/PENDING 增加补偿重试任务和人工核对视图。

验证：

```bash
cd backend && mvn test -Dtest=ProjectCreditServiceTest
```

补充压测建议：并发 20 个相同和不同 `requestId` 的兑换请求，确认无重复入账、无长时间锁等待。

### 2. AI 流式接口可能耗尽线程资源

`/api/ai/chat/stream` 使用 `CompletableFuture.runAsync` 默认公共 ForkJoinPool，并在发送每 2 个字符后 `Thread.sleep(30L)`。如果 AI 返回较长文本，每个请求都会长时间占用线程。

建议：

- 使用受控 `TaskExecutor`，设置核心线程、最大线程、队列长度和拒绝策略。
- 去掉固定 `sleep`，改为基于真实上游流式返回或批量 chunk。
- 为用户/IP 增加并发上限、超时和取消回调。

验证：

```bash
rg -n "CompletableFuture.runAsync|Thread.sleep|SseEmitter" backend/src/main/java
```

### 3. 房间写操作全局串行

`RoomService.joinRoom` 和 `RoomService.addAi` 是 `synchronized` 方法，锁粒度是整个 service 实例。不同房间的入座和添加 AI 会互相阻塞；多实例部署时该锁又不能跨实例生效。

建议：

- 改为数据库行锁或基于 `roomId` 的细粒度锁。
- 对 `rooms` 增加乐观锁版本字段，失败时返回可重试错误。
- 多实例前必须避免依赖 JVM 内锁保护房间容量。

验证：

```bash
rg -n "synchronized .*joinRoom|synchronized .*addAi" backend/src/main/java
```

### 4. 查询与数据生命周期缺少上限

大厅房间列表直接返回某游戏全部房间；游戏列表计算在线人数时按游戏查房间再 sum seat。实时连接和限流状态用内存 Map 保存，无过期清理。

建议：

- 房间列表默认只返回 `WAITING` 或最近 N 条，并支持分页。
- 用聚合查询或缓存维护游戏在线人数。
- `ChatRateLimiter` 和 `PlayerConnectionService` 增加定期清理，删除长时间未活跃玩家。
- 管理员 session 改用 Redis 或 TokenStore，并定期清理过期 token。

### 5. 部署与 schema 稳定性

默认 `SPRING_JPA_HIBERNATE_DDL_AUTO:update` 适合开发，但在测试服/正式服会带来不可预期 schema 变更。项目已有 SQL 文件，应逐步转向显式迁移。

建议：

- 非 local profile 下强制 `ddl-auto=validate`。
- 引入 Flyway/Liquibase 或维护版本化 SQL 迁移。
- 构建脚本部署前执行 schema 校验，失败时中止上线。

## 稳定性测试建议

- 后端单测：账务兑换幂等、重复 requestId、远程失败后本地状态。
- 并发测试：同房间多人同时加入、不同房间同时加入、房间满员边界。
- SSE 压测：并发 50 个流式 chat，观察线程数、响应超时和拒绝策略。
- 长跑测试：模拟 1 万个不同游客连接/发言，观察 `ChatRateLimiter` 与 `PlayerConnectionService` 内存曲线。
- 部署检查：测试/正式环境启动时禁止默认密码、禁止 `ddl-auto:update`。
