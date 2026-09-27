# 逐项实现与验收索引

本索引以原始审计编号为主键。状态以 `remediation-status.md` 为准；“已实现”不代表专项负向、兼容和集成验证全部通过。路径均相对仓库根目录。证据日志位于本目录的 `evidence/`。

| 编号 | 批次 | 实现位置 | 数据迁移影响 | 已有证据与剩余限制 |
|---|---|---|---|---|
| MNT-001 | 1 | `backend/sql/migrations.json`；`scripts/ci/production_migration_manifest.py`；发布生成器/执行器 | 单一版本化清单；保留原始基线字节；新增SQL依清单执行 | 实际生成计划、新旧库、部分DDL续跑、重复执行及缺列/索引负向已在隔离MySQL通过；不授权改写现网历史ledger |
| MNT-002 | 1 | `frontend/package.json`；`frontend/scripts/test-typecheck.mjs`；`scripts/windows/Build-Local.ps1`；CI phase | 无 | app/node检查与两类负向夹具；保留宽松类型设置；构建失败退出码及锁图配置指纹补强 |
| SEC-007 | 1 | 前端锁图、`pnpm-workspace.yaml`及CI pnpm audit | 无 | 锁图零公告；保留lint警告基线且拒绝新增；当前警告108条 |
| SEC-008 | 1 | `backend/pom.xml`；`backend/sca-suppressions.xml`；`scripts/ci/prepare-maven-plugin-sca.py`、`verify-maven-plugin-sca.py`；`NettyTransportSecurityTest` | 无 | NVD 更新有效；Boot 4.1.1、Connector/J 26.7、Jackson 2 兼容；运行及测试依赖加 223 个实际解析插件 JAR 通过 CVSS≥7 阻断；精确 SHA/CVE 误报与到期见 `remediation-maven-sca.md`；L2 与实打 JAR 版本证据已补齐，目标共享 MySQL 版本仍属发布前独立门禁 |
| SEC-001 | 2 | WebSocket配置、STOMP拦截器、`AuthenticatedSocketRegistry`、`RoomAccessPolicy`、`ReplayArchiveService`；`NativeWebSocketIntegrationTest` | 第7迁移新增归档权限字段/参与者关联；未知历史权限保持未知 | 原生 WS/SockJS 真实 STOMP 跨用户/直接 broker 发布拒绝后断连，私密状态/回放定向通过；缺完整 Redis/SSO/浏览器联测 |
| SEC-002 | 2 | `AuthService`；`service/token`；`AuthenticatedSocketRegistry`；前端认证服务 | Redis新命名空间；旧令牌清理；原SSO会话绑定令牌记录 | 双设备隔离、幂等注销及原生 WS 注销断连通过；真实 Redis 及上游会话到期周期复核端到端仍待验收 |
| SEC-004 | 2 | `CommunityService`；`WriteRateLimiter`；社区API/页面 | 第7迁移新增用户＋帖子唯一点赞关系；保留旧点赞总数 | MySQL24并发点赞只增加6主体；标签/登录限制已实现；真实Redis故障与原子限流集成仍待执行 |
| SEC-005 | 2 | `RoomService.joinRoom`；房间投影查询；`WriteRateLimiter`；`ClosureMySqlCommunityTest` | 无 | BCrypt移出锁；锁内刷新复核；MySQL 大小写配额、最后席位双并发只有一人成功、口令预检后更新导致 `ROOM_PASSWORD_CHANGED` 均通过，见 `evidence/remediation-concurrent-last-seat.json` 和 `evidence/remediation-changed-password-after-preflight.json`；真实 Redis 限流联测尚缺 |
| SEC-006 | 2 | `AuthService`正常CA/主机名校验及HTTP超时 | 无 | 本地真实自签名HTTPS被拒绝且服务未收到授权码；未连接真实SSO服务 |
| SEC-003 | 3 | 权威 `aienie-doc/service-integration/ai-service` 预算契约；service/caller proto；`AiProxyService`、`AiGrpcClient` 及服务端凭据拒绝保护 | 预算执行/项目积分预留表尚未完成 | 契约checker、三种通用 AI 入口和底层聊天/向量/OCR 零上游调用负向通过；游戏 AI 在预算能力缺失时只做本地合法兜底。持久化执行、预留、幂等结算、重启对账未完成；不能宣称收费能力可用 |
| PERF-001 | 3 | AI/User/Billing gRPC客户端及前端AI请求超时 | 无 | 5秒普通RPC/45秒AI deadline、真实取消通过；保留V2总预算；仍需所有能力的完整预算/重启验收 |
| PERF-002 | 3 | `RoomService`添加AI入座流程 | 无 | 本地昵称替代远程生成，短事务入座；最终三玩法兼容端到端未完成 |
| PERF-009 | 3 | `CancellableAiTask`；`AiStreamConcurrencyLimiter`；AI SSE控制器 | 无 | 唯一permit、取消前/后执行、永久阻塞RPC定向通过；完整HTTP SSE多回调端到端尚待执行 |
| PERF-003 | 4 | `service/v2/AiTurnCoordinator`、`RoomRepository`/`GameStateRepository` SKIP LOCKED | 无 | 64 房间批次、4 工作线程、有界恢复候选；隔离 MySQL 单房持锁不阻塞其他房时钟；压力与任务等待指标仍需完整验收 |
| PERF-004 | 4 | `frontend/nginx.conf`；`scripts/windows/Test-NginxWebSocket.ps1` | 无 | 官方 Nginx Windows 1.31.6 将仓库配置中的 `/ws`、`/ws/info`、SockJS WebSocket 路径代理到真实测试上游；101 升级、无 301；证据 `evidence/remediation-nginx-ws.json` |
| PERF-005 | 4 | `frontend/src/hooks/useGameSocket.ts`、`useRoomRuntime.ts`；服务端 `/app/sync` 与原生 WS 集成测试 | 无 | 代次、心跳、指数抖动、订阅后同步确认和真实原生 WS/SockJS 定向通过；浏览器断线窗口与状态合并完整端到端仍待执行 |
| PERF-008 | 4 | `StatsService`；三条新旧结算调用链；`User.coins` | 第8迁移新增稳定玩家锁和archive＋player结算收据；不伪造历史收据 | MySQL首次创建、20次并发/2归档、事务回滚重试、旧资料保存不覆盖奖励均通过；三玩法完整终局兼容验收仍待完成 |
| MNT-003 | 4 | `SettlementView`；运行时成就处理；`v2Social.ts` | 本地记录增加processedArchiveIds；保留旧计数 | 服务端本人胜负＋稳定归档ID；Web Locks同一记录防重；旧数据与并发去重单测通过；缺Web Locks时明确本地保存失败；刷新页面完整验收尚待执行 |
| MNT-004 | 4 | `v2Social.ts`；好友/结算面板；认证存储 | 保留不可读历史，禁止异常时覆盖 | 纯读不写、形状错误、配额/权限失败与重试测试通过；主布局存储拒绝的浏览器端到端待执行 |
| MNT-005 | 4 | `RuleSupport`、规则集、`V2GameService`；前端apiError/i18n/运行时 | 无 | 稳定错误码优先于文案；三语言映射通过；重复投票负向发现通用校验提前拦截后已修复并复跑；完整用户交互恢复待验收 |
| PERF-006 | 5 | `ArchiveParticipant`；`GameArchiveRepository` SQL过滤分页；`ClosureMySqlArchiveScaleTest` | 第7迁移关联表及保守历史回填；单页最多100 | 隔离 MySQL 8.4 下 1千与 10万归档分别返回100实体、准确总数 505/50495；索引/关联表计划见 `evidence/remediation-archive-scale-mysql84.json` |
| PERF-007 | 5 | `GameEvent.publicSeq`、`GameEventRecorder`、`GameLogQueryService`、`GamePlayController`、`V2GameService`；前端 `GameLogPanel`/版本刷新 | 第10迁移公开游标、回填与唯一索引；部分 DDL 续跑通过 | 权限过滤先于游标，私密事件没有公开序号；新 V2 局热日志持久化只保留100条，旧公开事件仍可分页读取；前端只挂载可见日志行。完整 `data.events` 仍存热状态，旧活跃对局转换尚未完成；不得称历史增长整改已关闭 |
| PERF-010 | 5 | `AiDecisionTrace.instanceId`、仓库按实例聚合、`ReplayArchiveService` | 第9迁移列/索引及可重入回填；无法归属旧记录保留为空 | 部分 DDL 续跑、已知归档 ID 回填、未知单列和新局只聚合本实例通过；见隔离 MySQL 8.4 `trace-instance-migration.json` |

最终可控完整端到端未完成。真实公共服务/模型冒烟未执行，仍缺账户、模型和累计费用上限；上述本地结果不能替代真实链路验收。
