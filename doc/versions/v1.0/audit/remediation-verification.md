# 整改复验记录（2026-09-27）

原三类审计报告保持基线。23 项当前状态见 [整改跟踪](remediation-status.md)，逐项代码、迁移及剩余限制见 [逐项验收索引](remediation-item-evidence.md)。本报告区分专项通过、代码完成待验收和未完成；标准构建不替代端到端负向证据。

## 已通过的本地门禁

- **构建与类型**：`Test-Local.ps1 -Level L2` 在 Boot 4.1.1/官方 gRPC 客户端下通过；包含前端 app/node 类型检查、负向夹具、lint 108 条存量警告门禁与打包指纹。2026-09-27 10:35 后端 585 项、547 通过、38 个真实环境门禁跳过、失败 0；前端 13 文件 72 项通过，见 `evidence/remediation-final-l2-20260927.log` 和对应 summary JSON。其后虚拟列表的稳定 key 与键盘可访问性微调另行通过定向 Vitest、TypeScript 和 lint 检查。
- **Maven SCA**：有效 `NVD_API_KEY` 从机器环境读取，没有输出或提交密钥。运行、测试、provided 依赖及 12 个固定构建插件实际解析的 223 个 JAR 进入 OWASP Dependency-Check 13；覆盖校验通过，CVSS 7 及以上剩余 0，低于阻断线 4 条。误报仅以实际 JAR SHA-1＋CVE、维护责任人和 2026-12-31 到期日登记；见 [Maven SCA 专项](remediation-maven-sca.md)、`evidence/remediation-effective-sca-final.log` 和 `evidence/remediation-effective-sca-summary.json`。
- **实打制品**：`evidence/remediation-boot4-packaged-libraries.json` 保存可执行 JAR 的 SHA-256 和所选依赖；包含 Boot 4.1.1、Connector/J 26.7.0、Netty 4.2.18.Final、Tomcat 11.0.26、gRPC 1.82.4、Protobuf 3.25.9 和 Jackson 2 兼容模块。发布前必须只读确认目标 MySQL Server 为 8.4+，本轮没有触及共享数据库。
- **迁移与并发**：版本化 manifest 生成执行计划、制品与 ledger；隔离 MySQL 8.4.11 验证新旧库、部分 DDL 后续跑、重复执行、缺列/索引负向、历史数据保留、社区并发点赞、双归档结算、玩家锁和 SKIP LOCKED 时钟。最新源码下 `evidence/remediation-final-mysql84-room-race.log` 退出 0，独立运行目录为 `C:/Users/duwei/AppData/Local/Aienie/artifacts/aisocialgame/mysql-f0dddf6ccddc4240ae58a3240fcea339`。最后席位双并发只有一人成功；预检后修改口令使锁内提交返回 `ROOM_PASSWORD_CHANGED`，见 `evidence/remediation-concurrent-last-seat.json`、`evidence/remediation-changed-password-after-preflight.json`。第 9/10 迁移分别验证 trace 归属及公开事件游标，私密事件公开序号为空。一次并发 Maven 测试污染目标目录导致的中间失败保留于前一运行目录；串行重跑通过。
- **规模**：MySQL 8.4 下 1 千/10 万归档的第一页只返回 100 实体，总数分别为 505/50495；`EXPLAIN` 使用归档索引和参与者主键等值连接，见 `evidence/remediation-archive-scale-mysql84.json`。这是本地数据分布下的结果，不推断线上延迟。
- **真实 WebSocket**：原生 WebSocket 与 SockJS `/ws/info`/transport 的本地 STOMP 集成测试通过，覆盖跨用户订阅、broker 直发被拒并断连、私密视角以及注销断连；见 `evidence/remediation-native-websocket-final.log`、`evidence/remediation-sockjs-websocket.log`。前端 fake socket 测试覆盖代次、心跳、抖动与同步握手。尚缺浏览器断网恢复、真实 Redis/SSO 共同端到端。
- **Nginx `/ws`**：`scripts/windows/Test-NginxWebSocket.ps1` 用官方 Nginx Windows 1.31.6，将仓库 `frontend/nginx.conf` 的上游和监听端口映射到隔离环回端口。`/ws`、`/ws/info`、SockJS WebSocket 子路径到达上游，原生和 SockJS 握手均返回 101、无重定向；见 `evidence/remediation-nginx-ws.json`。Linux 发布镜像不在本地验收范围。
- **AI 超时/拒绝**：真实 loopback TLS/gRPC 验证 CA、主机名、45 秒截止与取消；SSE permit 防重复释放、永不响应夹具取消通过。通用聊天、向量和 OCR 在预算缺失时于入口拒绝；底层 `AiGrpcClient` 同样拒绝所有无预算付费推理，网络层未收到请求；SSE 在响应头提交前返回稳定 `BUDGET_UNAVAILABLE`，前端保留该 code；游戏 AI 继续以本地合法动作兜底。见 `evidence/remediation-all-ai-fail-closed.log` 与最终 L2。这不是预算闭环完成证据。
- **日志接口与前端**：第 10 迁移添加按归档连续的公开游标、回填与唯一索引；`GET /api/games/{gameId}/rooms/{roomId}/logs` 先鉴权后分页、每页最多 100。新 V2 局热状态持久化只保留最近 100 条公开日志，旧日志继续从事件表分页读取；WebSocket 发送版本通知，前端窗口化挂载可见日志行，本地结算回放缓存改从完整服务端回放读取。`GameLogQueryServiceTest`、`V2GameServiceIntegrationTest`、`GameLogPanel.test.tsx` 定向通过，分别见 `evidence/remediation-hot-log-window-targeted.log`、`evidence/remediation-log-pages-frontend.log`。

## 仍未完成的关闭条件

1. **SEC-003 是主要阻断项**：ai-service 仍没有批准契约中的预算准备、可靠路由上限、持久化单次执行与状态查询；AISocialGame 也没有项目积分原子预留、权威 usage 结算、重启后对账。所有收费入口因此安全停用；不能宣称模型、OCR 或游戏 AI 的付费链路可用。预算能力完成前不得恢复旧的先调用后扣费路径。
2. **PERF-007 仍未关闭**：传输、公开游标、热日志和页面挂载数量已受限，V2 热 `data.events` 仍保存完整历史。旧活跃对局幂等转换、事件读取器替换 AI/规则对数组的直接依赖、长局状态体积基准尚未通过；没有直接裁剪事件，以免丢失 AI 观察或私密证据。
3. **最终可控端到端**：还需隔离 Redis、本地 SSO/gRPC、浏览器、HTTP SSE、原生 WS/SockJS、Nginx/MySQL 联测，特别是会话到期/上游撤销、Redis 故障拒绝、预算重启对账、一间房持锁下其他任务恢复、三玩法四种 Persona、刷新成就及三语言交互。房间最后席位与口令预检后变更已在隔离 MySQL 单项通过，组合链路仍待验收。本机没有可用 Docker/WSL Linux 或 Redis 服务，本轮尚未完成这些组合场景；逐项表中的“代码完成待验收”不得当成关闭。
4. **真实公共服务与付费模型冒烟**：未执行，账户、模型和累计费用上限尚未指定。本轮没有预发布或生产部署；“代码及可控验收完成”也尚不能标记，更不能标记真实链路完成。

早期失败证据仍保留：`evidence/remediation-access-l2.log` 暴露旧匿名回放断言，`evidence/remediation-frozen-l2.log` 暴露重复投票错误码，`evidence/remediation-upgrade-l2.log` 暴露构建指纹失效，`evidence/remediation-sca-final.log` 是升级前高危尚未清零的扫描；修复与后续成功记录不能抹去这些历史失败。
