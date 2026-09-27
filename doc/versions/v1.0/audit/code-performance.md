# AISocialGame 代码性能与运行稳定性审计

审计日期：2026-09-26。审计基线：`8b89c02954810708458719dbbc00f3e8fb71b19b` 加审计开始时的未提交工作区；以下行号对应该工作区，不应直接套用到纯 HEAD。审计只新增文档，没有修复业务代码，也没有部署、启动服务、访问实际账户或调用计费 AI。

## 1. 结论与范围

项目已具备行锁、游戏 AI 总时间预算、有界并发、事务外生成、分页入口和进程身份校验等基础保护，但这些保护尚未覆盖所有路径。优先处理普通 RPC 的超时/取消、添加 AI 时的持锁远程调用，以及游戏调度的串行阻塞；使用仓库 Nginx 代理 WebSocket 的部署路径还存在明确的 `/ws` 与 `/ws/` 匹配缺陷。

本报告包含 **10 项发现：P1 高 4 项、P2 中 6 项**。P1 表示在明确触发条件下可使关键流程失效或扩大故障影响，P2 表示并发正确性、数据量增长或恢复能力缺陷。严重性代表代码风险，不代表已经发生线上事故。

| 编号 | 级别 | 发现 | 适用范围 |
|---|---|---|---|
| PERF-001 | P1 高 | 多条 blocking gRPC 路径无 deadline，SSE 超时不取消实际工作 | 普通 AI、用户认证、账务调用 |
| PERF-002 | P1 高 | 添加 AI 在房间行锁及事务内远程生成昵称 | 房主添加 AI |
| PERF-003 | P1 高 | 全房间串行 tick 先于恢复及派发，一间房阻塞影响所有房间 | V2 调度器 |
| PERF-004 | P1 高，部署条件限定 | `/ws` 请求被 `/ws/` 代理 location 重定向，无法正常升级 | 流量经过仓库 Nginx 的路径 |
| PERF-005 | P2 中 | WebSocket 重连只订阅，不补读断线期间的状态 | 游戏页面网络抖动/服务重启 |
| PERF-006 | P2 中 | 回放第一页查询仍循环读取全部历史记录 | 普通及个人回放列表 |
| PERF-007 | P2 中 | 事件和日志无总量上限，持久化、推送及前端渲染反复处理全量 | 长时间对局，尤其海龟汤讨论 |
| PERF-008 | P2 中 | 点赞、战绩、金币的读改写缺少并发冲突保护 | 并发点赞、同一玩家跨房结算 |
| PERF-009 | P2 中 | SSE 限流释放不幂等，计数对象移除与获取存在竞态 | SSE 完成/超时/错误与并发请求 |
| PERF-010 | P2 中 | 每次结算加载房间全部历史 AI trace 后才按实例过滤 | 同一房间重复开局 |

覆盖了 Spring 控制器/服务/仓储、V2 游戏调度及三套规则、AI/gRPC 边界、回放与统计、React Query 与 WebSocket、游戏日志组件、Vite 入口、SQL 索引、容器装配与 Windows 启停脚本。公共服务的内部实现不在本项目审计范围，部署端覆盖配置和网关路由未通过线上探测确认。

## 2. 方法、证据与验证边界

- 使用 Aienie 开发工作流及集成规范。已确认 PowerShell 运行于 `Win32NT`，使用 `.codegraph-win` 索引进行结构/调用关系检查，并以源码、SQL 和配置的直接读取复核。
- 主审计已完成 CodeGraph 同步和系统矩阵校验；矩阵校验结果见 [evidence/system-matrix.log](evidence/system-matrix.log)。CodeGraph `explore AiTurnCoordinator V2GameService`、`callers generateName`、`callers archiveFinishedGame` 用于定位链路；工具中的同名符号结果没有未经源码核对就作为证据。
- 外部行为依据仅使用官方文档：gRPC 默认无 deadline、Nginx 尾斜线重定向和 WebSocket 空闲超时。相应链接附在具体发现中。
- **发现均为源码可达路径、并发时序或复杂度的静态分析。没有实测吞吐、P95/P99、GC、SQL 执行计划、锁等待时长或实际 WebSocket 断线率。** 文中的数量级是算法推导，验收项是后续应执行的验证，不是本次已经通过的测试。
- 主审计统一执行本地构建/测试，结果以总报告及其 evidence 为准。本分项未重复启动 Maven/Vite 测试，也未进行压测。已有单测成功不能证明生产数据规模下没有这些缺陷。

## 3. 逐项发现

### PERF-001：普通 RPC 缺少 deadline，SSE 生命周期与后台工作脱节

**级别：P1 高；证据置信度：高（代码和官方默认语义）；发生频率未测量。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/integration/grpc/client/AiGrpcClient.java:60–92`：五参数重载传入 `deadlineSeconds=0`；仅正数时调用 `withDeadlineAfter`。`listModels`（39–43）、`embeddings`（128）、`ocrParse`（167）直接使用 blocking stub。
- `backend/src/main/java/com/aisocialgame/integration/grpc/client/UserGrpcClient.java:38–44`：`validateSession` 无 deadline；同类其他 RPC 亦未设置。
- `backend/src/main/java/com/aisocialgame/integration/grpc/client/BillingGrpcClient.java:60–83`：余额查询直接阻塞调用；`BillingGrpcChannelConfiguration.java:12–16` 只禁用 retry，没有设 deadline。
- `backend/src/main/java/com/aisocialgame/controller/AiController.java:61–87`：SSE 设 60 秒超时，三个生命周期回调只释放计数，后台任务没有可取消的 Future 或 RPC Context。
- `backend/src/main/java/com/aisocialgame/service/AuthService.java:38,226–244`：认证位于事务中，先读本地用户，再执行远程会话验证。

**触发链与影响**

上游接受 RPC 后长时间不返回 → 普通聊天/昵称/认证/账务请求停在 blocking stub → 请求线程或 AI 专用工作线程持续被占用。认证已经访问数据库，远程等待还延长本地事务/连接占用。前端 8 秒 Axios 超时或 SSE 60 秒超时并不会自动取消上述独立调用；用户重试进一步增加未完成工作。线程池的有界队列最终会拒绝新请求，但不能回收被无限等待占住的线程。

gRPC 默认不设置 deadline，因此代码不能依赖库提供隐式超时。此结论基于 [gRPC 官方 deadline 文档](https://grpc.io/docs/guides/deadlines/)。配置中心可能另有配置，但审计未取得其实际覆盖证据，不能将未知覆盖视为已有保护。V2 `AiTurnGenerator.java:24–25,90–97` 已有 50 秒总预算及单 RPC 上限，应保留并作为其他入口的改造参考。

**建议**

为所有 RPC 定义按操作分类的有限 deadline，并受入口剩余预算约束；SSE 断连、超时和错误时传播取消到实际 RPC/任务。将认证的远程验证移出数据库事务，成功后用短事务合并必要的本地资料。对有副作用的调用遵循原服务幂等契约处理“不确定成功”，不要简单自动重试。

**验收**

使用本地可控、永不返回的 gRPC stub，覆盖 chat/models/embeddings/ocr、认证及账务读请求；确认每条路径在配置预算内结束，SSE 断连后后台调用被取消，工作线程和数据库连接恢复。故障解除后新请求应可成功。该验证无需真实上游或付费调用。

### PERF-002：添加 AI 时持有房间行锁等待远程昵称

**级别：P1 高；证据置信度：高。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/service/RoomService.java:30,146–174`：类级事务，`addAi` 首先 `getRoomForUpdate`，随后第 163 行远程生成昵称，之后才保存座位并返回。
- `backend/src/main/java/com/aisocialgame/service/AiNameService.java:32–62`：优先 RPC，异常发生之后才回退本地昵称；第 47 行使用 PERF-001 中无 deadline 的重载。
- `backend/src/main/java/com/aisocialgame/repository/RoomRepository.java:25–27` 的 `findByIdForUpdate` 使用 `PESSIMISTIC_WRITE`；`V2GameService.java:164` 的 tick 也请求该房间锁。

**触发链与影响**

房主添加 AI → 房间加写锁 → 等待昵称 RPC → 其他 join/addAi/start/tick 需要同一房间行锁，无法推进。昵称只是展示属性，却将数据库锁持有时间绑定到 AI 延迟；多房间并发添加 AI 可同时占用连接，并与 PERF-003 形成跨房间影响。已有异常回退只处理“返回异常”，无法处理没有返回的调用。

**建议**

先使用本地昵称在短事务中入座；如确需 AI 昵称，提交后异步更新。另一方案是在事务外有限时生成，然后进入短事务重新校验房主、等待状态、空位与幂等身份。不能把加锁前的空位判断作为最终写入依据。

**验收**

阻塞模拟昵称服务；同房间的读取/合法状态操作不得因昵称长期持锁，其他房间的时钟必须继续运行；并发添加 AI 仍不能超员或重复座位。断言远程昵称调用时 `TransactionSynchronizationManager.isActualTransactionActive()` 为 false。

### PERF-003：全房间串行调度导致阻塞扩散

**级别：P1 高；证据置信度：高；最大房间容量尚未测量。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/service/v2/AiTurnCoordinator.java:44–58`：每次 pulse 先遍历全部活跃房间同步 tick，再恢复 RUNNING job，最后派发 QUEUED job。
- `backend/src/main/java/com/aisocialgame/repository/GameStateRepository.java:23–24`：`phase <> 'SETTLEMENT'` 全量活跃房间查询，无批次限制。
- `backend/src/main/java/com/aisocialgame/service/v2/V2GameService.java:162–179`：tick 依次取得房间和状态悲观锁，构造整个公开状态的 JSON，再决定是否发生变化。

**触发链与影响**

某一活跃房间的事务正在持锁（例如加入私密房间的昂贵校验、结算或其他并发写）→ pulse 在该房间等待 → 后续房间到期转换、超时恢复和 AI 派发全部推迟。即使没有锁竞争，单轮工作量仍随活跃房间数量及状态体积增长。`fixedDelay=750ms` 是一次执行结束后的等待时间，不是所有房间都能在 750ms 内推进的保证。

**建议**

按到期时间/待处理标记选择有界批次，房间级任务采用有限锁等待或跳过已锁行；将 job 恢复/派发与时钟扫描隔离，设置每轮时间预算与公平性。若未来扩实例，还需明确调度租约/分片和本地 WebSocket broker 的路由方案，不能只增加应用副本。

**验收**

用可控数据库事务持有一间房的锁，同时创建多个其他房间；其他房间应在明确的延迟目标内完成到期推进及 job 派发。记录锁等待、单轮 pulse 时间、最老队列等待和恢复延迟，验证慢房间不会饿死其余房间。MySQL 专项应在隔离测试库执行。

### PERF-004：仓库 Nginx 的 WebSocket 路径触发尾斜线重定向

**级别：P1 高（只适用于经过该 Nginx 的路径）；证据置信度：高，现网是否走此路径未验证。**

**证据位置**

- `frontend/src/hooks/useGameSocket.ts:86`：客户端连接 `/ws`。
- `frontend/nginx.conf:15–20`：仅 `location /ws/` 配置 Upgrade 头和 `proxy_pass`，没有精确 `/ws` location。
- `frontend/Dockerfile:12`：复制此 Nginx 配置；`scripts/ci/assemble-aisocialgame-runtime.sh:26` 和 `assemble-aisocialgame-production-runtime.sh:16` 装配同一文件。
- `backend/src/main/java/com/aisocialgame/config/WebSocketConfig.java:32–33`：后端注册 `/ws`。

**触发链与影响**

浏览器向前端容器发起 `GET /ws` 升级请求 → Nginx 对带尾斜线且使用 proxy_pass 的 prefix location 执行自动补斜线 301 → 未得到 WebSocket 所需的 101 升级响应。客户端每 3 秒重试，页面初始 HTTP 请求可能成功而持续状态推送不可用。Nginx 官方明确描述了这一 [location 尾斜线处理规则](https://nginx.org/en/docs/http/ngx_http_core_module.html#location)。

本项不等同于“本地域名或现网必然失效”：Vite 开发代理以及网关直接把 `/ws` 转到后端的路径可能绕过此配置。本次未访问已部署环境，保留该适用条件。

**建议**

为 `/ws` 增加精确的升级代理规则，并保留需要的子路径代理；增加运行时装配后的路径契约测试。另明确协商并实际发送心跳：客户端虽声明 `heart-beat`（`useGameSocket.ts:97`），没有心跳发送/检测计时器，后端简单 broker 配置也未显式开启心跳。未配置时的 Nginx 上游空闲超时默认 60 秒，见 [官方 WebSocket 代理说明](https://nginx.org/en/docs/http/websocket.html)，不能仅靠 CONNECT 中的声明维持空闲连接。

**验收**

在被批准的测试部署路径验证 `/ws` 首个响应为 101、没有 301；等待超过代理空闲窗口后仍有双向心跳，断网可检测并恢复。Vite 测试不能替代容器 Nginx 路径测试。

### PERF-005：重连后缺少状态补读，页面可能停留在旧局面

**级别：P2 中；证据置信度：高。**

**证据位置**

- `frontend/src/hooks/useGameSocket.ts:105–117`：CONNECTED 分支只更新连接标志并发送订阅，没有触发快照同步。
- `frontend/src/hooks/useGameEngine.ts:25–30`：`refetchInterval: 0`。
- `frontend/src/pages/games/shared/useRoomRuntime.ts:52–78,81–86`：收到事件时失效缓存，或玩家/房间变化时 refetch；没有监听 socket 从断开到连接的状态补读。

**触发链与影响**

游戏页面保持焦点 → WebSocket 短暂断开 → 期间发生投票结果或结算 → 重连重新订阅 → 若此后没有新广播，丢失的事件不会重发，缓存仍是旧状态。React Query 的浏览器焦点/网络恢复机制可能在部分场景触发 refetch，但“仅 WebSocket 中断，HTTP 网络持续在线”不保证触发这些机制。

**建议**

订阅建立后显式补读房间和当前用户视角的 game-state；采用连接代次与状态版本/事件序号，避免旧连接回调覆盖新连接状态。为重连加入指数退避与 jitter，防止服务恢复时所有客户端每 3 秒同步重试。必要时提供低频、可见页面限定的恢复轮询。

**验收**

用 Mock WebSocket 断开消息通道，保持 HTTP 在线且页面不失焦；期间后端进入 SETTLEMENT，再发送 CONNECTED、不发送任何后续 MESSAGE。页面必须主动获取并展示结算状态；重复重连不能生成重复 socket 或持续重试旧房间。

### PERF-006：回放分页只限制返回量，没有限制读取量

**级别：P2 中；证据置信度：高。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/controller/ReplayController.java:30,40`：普通列表与个人列表都调用 `search`。
- `backend/src/main/java/com/aisocialgame/service/ReplayArchiveService.java:92–113`：在单个只读事务内，从第 0 批开始、每批 200 个实体循环到 `!rows.hasNext()`；玩家和时间过滤在 Java 内执行，选满当前页仍继续读取，以计算 total。
- `backend/src/main/java/com/aisocialgame/repository/GameArchiveRepository.java:10`：仓储仅按 gameId 分页，未表达时间和参与者过滤。

**触发链与影响**

历史对局持续增加 → 任意第一页/小页请求仍扫描匹配 gameId 的全部历史实体及 JSON → SQL 往返、反序列化和事务时间随历史总量增加。分成 200 条批次只限制单次查询返回，并不限制整个请求工作量；同一 JPA 持久化上下文还可能保留已读实体。每页使用 Page 查询，也可能产生额外 count SQL。即使不传任何筛选条件，控制器仍走这条路径，而不是已有的数据库分页 `list` 方法。

**建议**

首先将无玩家筛选的常见路径改为数据库条件分页，时间条件下推。为参与者建立可索引关联表或适当投影/索引，以数据库查询得到当前页与总数；大历史列表可采用游标分页。不要用“只读事务”或“每批 200 条”作为内存/响应时间上限。

**验收**

用隔离数据集分别放入 1 千与 10 万归档，读取第一页：SQL 次数和返回实体数应保持有界，而不是按总记录数增加；筛选结果、分页总数和稳定排序保持正确。记录 SQL 数量和扫描行数，不能只验证 JSON 返回了 20 条。

### PERF-007：完整事件/日志重复序列化、传输和渲染，长局成本不断增长

**级别：P2 中；证据置信度：高；达到用户可感知卡顿的规模未测量。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/engine/v2/RuleSupport.java:79–94`：事件追加到 `data.events`，公开日志追加到 `state.logs`，未裁剪总量。
- `backend/src/main/java/com/aisocialgame/engine/v2/turtlesoup/TurtleSoupRuleSet.java:128–139,170–172`：讨论记录本身限制 80 条，但每次讨论仍生成永久累积的事件/日志，且没有自动结束时限。
- `backend/src/main/java/com/aisocialgame/service/v2/V2GameService.java:173,284–305,336–343,428`：tick 序列化全状态；每次保存状态含完整 JSON；公开变化广播完整 state，同时向真人推送刷新通知；响应含全部 logs。
- `frontend/src/pages/games/shared/useRoomRuntime.ts:65–74`：推送会触发 HTTP 补读；`frontend/src/pages/games/shared/GameLogPanel.tsx:23–35`：map 全部日志，ScrollArea 只提供滚动容器，没有列表虚拟化。

**触发链与影响**

用户持续进行合法 DISCUSS → events/logs 不断增长 → 后续每个状态快照及存储重写都包含历史前缀 → 累积传输和序列化成本可达到关于事件数的二次增长；每个客户端还反复接收全量广播并补读全量 HTTP 状态。服务端保存了独立 `game_events`，但同时在热状态保留全部事件副本。AI prompt 中的投影/压缩不能解决数据库热状态、WebSocket 载荷和浏览器 DOM 的增长。

**建议**

将完整回放保留在事件表；热状态保留窗口和事件游标，日志支持增量拉取/分页。推送版本号及增量，合并同一版本的公共/私有刷新，避免直接用公开状态覆盖私有视角。前端虚拟化长日志，并为长期无人推进的房间设计可恢复的归档/休眠策略。

**验收**

构造大量合法讨论事件，比较 100、1 千、1 万条日志时的状态响应字节数、保存字节数、push 数量及渲染节点数。热状态和可见 DOM 应有明确上限，完整回放仍可按游标取得。该建议不要求截断业务证据。

### PERF-008：战绩、金币和点赞的读改写会丢失并发增量

**级别：P2 中；证据置信度：高（并发时序），未进行真实 MySQL 并发复现。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/service/StatsService.java:26–35,45–71`：先读 PlayerStats/User，再在 Java 中 `+1`/增加 score/coins，最后 save。
- `backend/src/main/java/com/aisocialgame/repository/PlayerStatsRepository.java:13`：普通查询，没有写锁；`model/PlayerStats.java` 和 `model/User.java` 都没有 `@Version`。
- `backend/src/main/java/com/aisocialgame/service/CommunityService.java:58–61`：点赞同样 read-modify-write；`model/CommunityPost.java` 没有 `@Version`。
- `backend/src/main/java/com/aisocialgame/service/v2/V2GameService.java:298–301`：结算调用 stats；锁的是当前房间/状态，不能保护跨房间共享的玩家行。

**触发链与影响**

两个请求同时点赞，或同一真人参加的两个房间同时结算 → 两个事务读取相同旧值 N → 各自保存 N+1 → 后提交者覆盖前一增量。事务保证单次操作的原子提交，不会自动把应用层的两次读改写合并。首次并发创建同一统计主键还可能产生唯一键冲突并回滚结算。金币是项目本地 `User.coins`，本项没有将其误称为 pay-service 账务余额。

**建议**

使用数据库原子增量及安全 upsert；或按稳定顺序锁定共享统计/玩家行。若选 `@Version`，必须配合有界重试和对局结算幂等键。战绩/奖励宜以“实例 + 玩家”唯一记录作为可追溯事实，避免重试重复奖励。

**验收**

在真实 MySQL 隔离测试库用 barrier 同步两个事务读阶段，验证并发点赞增量不丢失、同一玩家双房结算累计两局、首次统计创建不使结算失败；再次执行相同实例结算不得重复奖励。

### PERF-009：SSE 并发计数存在重复释放和移除竞态

**级别：P2 中；证据置信度：高。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/controller/AiController.java:66–68`：completion/timeout/error 三个回调分别无条件 release 同一 userId，没有一次性 lease。
- `backend/src/main/java/com/aisocialgame/service/AiStreamConcurrencyLimiter.java:19–28,32–39`：获取时先得到 AtomicInteger 引用再 CAS；释放时先 decrement，再条件 remove。

**触发链与影响**

存在两个独立缺口：

1. 生命周期回调可能先报告超时/错误、随后报告完成，同一 SSE 没有幂等保护，会把其他仍存活请求的计数减掉。此时新请求可在已有工作仍运行时再次进入。
2. 即使每个请求只释放一次，也存在确定的交错：A 将计数从 1 减至 0，尚未 remove；B 取得同一 counter 并 CAS 成 1；A 随后 `remove(userId,counter)` 成功，B 的活动计数从 map 消失；C 创建新 counter。`ConcurrentHashMap` 与 `AtomicInteger` 各自线程安全，不能让跨对象这组操作自动原子化。

因此默认“每用户 2 个”的限制不是可靠上限。此处与 PERF-001 不重复：即使 RPC 都能按时返回，计数本身仍可能不正确。

**建议**

获取/释放通过同一 key 的原子 `compute` 或专用 permit 结构完成，返回唯一 lease；每个 lease 以 CAS 保证最多释放一次。明确 permit 衡量的是实际后台工作还是 SSE 连接，不能连接结束后把仍运行的后台工作当成已释放。

**验收**

用 latch 控制上述交错，断言活动 permit 永远不超过上限；同一请求依次触发 timeout、error、completion，计数只减一次；高并发 acquire/release 后无负数、无残留计数，也不能误删另一活动请求。

### PERF-010：重复开局的结算读取全部历史 AI trace

**级别：P2 中；证据置信度：高。**

**证据位置**

- `backend/src/main/java/com/aisocialgame/service/ReplayArchiveService.java:68,187–199`：结算摘要先按 roomId 读取所有 trace，再在 Java 用 `quality.instanceId` 过滤本次实例，并计算历史未归属记录数。
- `backend/src/main/java/com/aisocialgame/repository/AiDecisionTraceRepository.java:11`：返回没有分页的实体 List。
- `backend/src/main/java/com/aisocialgame/service/v2/V2GameService.java:185–188,298–301`：调用归档时处于持房间/状态锁的结算事务中。
- `backend/sql/ai_quality.sql:47`：存在 `(room_id,id)` 索引，但该索引不能减少同房间所有历史 trace 的返回量。

**触发链与影响**

同一房间反复开局 → 旧 trace 持续累积 → 每次新结算都加载历史全部 JSON（包含 memory/quality 等）→ 结算锁持有时间和内存分配随房间历史增加，并会拖慢 PERF-003 的串行时钟。已有 roomId 索引能定位记录，但不能解决读取结果无上限的问题。

**建议**

将 instanceId 提升为可索引的独立列，数据库直接按实例聚合数量/fallback 等摘要；历史无归属记录单独聚合或后台维护。定义 trace 保留及归档政策，保留需要的审计关联，避免在热结算事务中反复扫描历史。

**验收**

给同一房间放入大量旧实例 trace，再结算只含少量 trace 的新实例；SQL 返回应只有聚合或该实例所需数据，耗时/内存不应跟整个房间历史线性增长；摘要仍不能混入旧局数据。

## 4. 已存在的保护与不应重复报错的点

- `AiTurnCoordinator` 虽使用 fixed thread pool，但其前置 Semaphore 将提交数量限制在 worker 数量内（1–8）；不能只因为 Java 默认线程池队列无界就断言该队列实际无界增长。派发拒绝时也有 permit 释放和 job 失败处理。
- 游戏 V2 把模型生成放在状态事务之外；生成最多两次尝试，共享有限时间预算；完成时重新锁定并检查实例、当前回合和截止时间，旧结果不会直接提交到新局面。
- 房间加入/添加已使用房间行锁，Room 有版本字段；本报告发现的是持锁时机和其他实体的并发增量问题，不是“所有房间都没有并发保护”。
- 房间列表有分页/上限，在线人数使用聚合；社区列表 Top50、排行 Top20、聊天前端保留 100 条。不能把这些入口误报为全表返回。
- `ProjectCreditService` 的兑换远程调用已有事务分段；本报告没有把该路径归为“全部远程账务调用都在锁内”。已成功远程调用而本地写入失败的补偿一致性应结合安全分项及账务契约核查。
- Windows `Start-Local.ps1` 对启动等待有时间上限，隐藏后台进程，校验进程路径及开始时间，并拒绝接管无记录的占用端口；`Stop-Local.ps1` 应维持这些身份校验。未实跑启停，不能声明所有崩溃恢复路径已通过。
- 前端 `main.tsx` 已按管理/用户入口动态分包；用户入口内仍静态引入多数页面，但没有本次包大小与浏览器性能测量证据，暂列优化候选，不直接给出“首屏一定慢”的缺陷结论。

## 5. 补充观测缺口与建议验收顺序

`backend/src/main/resources/application.yml:35–44` 默认关闭全部 metrics，只单独启用启动、运行时间和 HTTP 请求指标。仓库可见配置未启用 JVM/GC、Hikari、线程池及游戏调度指标；配置中心实际覆盖未知。建议在已有低基数指标约束内补充 pulse 时长、队列最老年龄、job 终态、RPC deadline/cancel、连接池等待与 WebSocket 重连指标。不要给用户 ID、房间 ID 或请求 ID 设置高基数指标标签。

| 顺序 | 工作 | 完成判断 |
|---|---|---|
| 1 | PERF-001/002/003 的超时、事务边界与调度隔离 | 人工阻塞上游/一间房时，其他房间仍推进，资源可恢复 |
| 2 | 按实际部署路径验证 PERF-004，修复 PERF-005 | 握手 101，空闲/断线恢复后自动补读正确私有视角 |
| 3 | PERF-008/009 并发正确性 | 确定性交错测试验证不丢增量、不超发 permit |
| 4 | PERF-006/007/010 数据规模治理 | SQL/载荷/DOM 有界，历史增长不拖慢热路径 |
| 5 | 经批准的容量与故障演练 | 以明确房间数、玩家数、模型延迟和资源限额记录 P95/P99，而非只记录通过/失败 |

本次没有容量数据可以支持具体 SLA 或并发上限。后续压测应先确定代表性数据规模与可控 AI 延迟，分别测试数据库、调度和消息恢复，避免让外部模型费用或波动掩盖本项目瓶颈。
