# AISocialGame v1.0 代码安全审计

审计日期：2026-09-26（Asia/Shanghai）  
基线：`8b89c02954810708458719dbbc00f3e8fb71b19b` 加审计时当前工作区的未提交修改。行号指本次工作区，不是仅指 HEAD。  
范围：Spring Boot 后端、React 前端、HTTP/STOMP 入口、普通用户与管理员身份、游戏和回放权限、AI/积分调用链、存储及日志、依赖锁文件、构建与运行脚本。仅新增审计材料，没有修改业务代码、部署、探测真实用户或执行付费 AI 调用。

## 1. 结论与严重度

发现 **3 项高风险、5 项中风险**。应优先修复 WebSocket 目的地授权、普通用户会话撤销与绑定、AI 项目积分准入。管理员认证、回放私有视角和敏感字段响应隔离已有实质防护，不能由这些防护推导所有通道均安全。

| 编号 | 严重度 | 问题 | 证据等级 |
| --- | --- | --- | --- |
| SEC-001 | 高 | STOMP 只认证 CONNECT，没有订阅与发布授权 | 代码确认；主审独立复核；未在线利用 |
| SEC-002 | 高 | 普通注销没有撤销令牌，旧令牌跟随用户最新 SSO 会话 | 代码确认；主审独立复核 |
| SEC-003 | 高 | AI 在积分检查之前执行，OCR 缺失项目计账与 usage | 代码及契约确认；费用影响有外部前提 |
| SEC-004 | 中 | 匿名社区写入和点赞缺少可靠滥用控制，tags 无界 | 代码确认；外部网关补偿控制未验证 |
| SEC-005 | 中 | 私密房间口令可连续猜测，BCrypt 在房间排他锁内执行 | 代码确认；未执行爆破或压测 |
| SEC-006 | 中 | SSO 回环 HTTPS 失败后自动采用信任任意证书的客户端 | 代码确认；触发依赖特定配置与本机条件 |
| SEC-007 | 中 | 前端依赖存在已知安全公告，pnpm 路径未见审计门禁 | 锁文件扫描及上游公告；可达性分层说明 |
| SEC-008 | 中 | 后端实际解析 protobuf-java 3.25.1，落入已知 DoS 版本范围 | 离线 dependency tree 及供应商公告确认 |

严重度是对本项目的影响评估，区别于依赖公告给出的 CVSS/严重度。没有宣称已发生入侵、真实公网可利用、数据库泄露或任意代码执行。公开接口或匿名使用本身不自动构成漏洞；具体缺少的约束见下文。

## 2. 方法与证据边界

- 读取仓库 `AGENTS.md`、`aienie-dev-workflow`、`security-review`、`aienie-integration` 及相关规范。公共服务参照同工作区 `aienie-doc/service-integration/user-service/{README,http,grpc}.md` 和 `ai-service/{README,grpc}.md`，不以本项目旧 proto 取代公共契约。
- 主审先执行 CodeGraph 状态检查及同步；等待期间读取配置、协议和精确字面量，随后通过 `.codegraph-win` 的 `AuthService`、`WebSocketAuthChannelInterceptor` 节点核对调用方，再直接阅读全文。CodeGraph 只作结构索引，不以其摘要代替证据。
- 用 `rg` 检查 SQL/进程执行/反序列化/HTML 注入/浏览器存储/证书绕过/凭据模式，人工沿高风险入口追踪。秘密模式检查只输出文件名、不输出匹配内容；没有把任意密码配置全文收集进报告。
- 前端采用实际管理器 `pnpm audit --json`，而非为运行 npm audit 创建另一套 package-lock。命令退出 1 表示发现公告；扫描详情已裁剪为 [security-dependencies.json](security-dependencies.json)。未执行自动升级或 `audit fix`。
- 后端执行离线 `mvn -o dependency:tree`，结果见 [security-maven-dependencies.txt](security-maven-dependencies.txt)。第一次调用受 PowerShell 未引用参数解析影响失败，引用 `-D` 参数后成功；本报告采用成功结果。未执行全量 Maven SCA，因此没有“后端只有一个 CVE”的结论。
- 未运行在线漏洞探测、房间爆破、真实 SSO 注销/扣费实验或生产网关测试。主审负责统一构建与测试记录，本报告不把未执行的验收用例写成通过。

## 3. 详细发现

### SEC-001 — STOMP 缺少消息类型及目的地授权（高）

**位置：** `backend/src/main/java/com/aisocialgame/websocket/WebSocketAuthChannelInterceptor.java:26`；`backend/src/main/java/com/aisocialgame/config/WebSocketConfig.java:24`、`:38`；`backend/src/main/java/com/aisocialgame/websocket/GamePushService.java:27`、`:41`、`:48`；`backend/src/main/java/com/aisocialgame/controller/RoomChatController.java:67`。

**调用链与条件：** `/ws` → 入站拦截器只在 `CONNECT` 调用 `AuthService.authenticate`，其他命令原样通过 → simple broker 接收 `/topic`、`/queue`。普通已登录用户可指定任意已知房间主题进行 `SUBSCRIBE`；还可向 broker 主题直接发送 `SEND`，避开只处理 `/app/room/{roomId}/chat` 的成员资格、限频、内容审查与服务端身份填充。公开房间列表提供可用的房间 ID，攻击者不需要猜 UUID。Origin 白名单不等于用户/房间授权。

**影响：** 非成员可接收房间广播（包括加密口令房间的广播），并伪造聊天、座位或状态推送，损害显示完整性与用户信任。这里不声称伪造广播会直接修改数据库游戏状态。另有连接建立后会话过期/注销不能重新校验的问题，应与 SEC-002 一并处理。

**建议：** 对入站帧默认拒绝；显式允许必要控制帧，`SEND` 仅允许经过处理器的应用目的地；拒绝客户端向 `/topic/**`、`/queue/**` 和其他用户地址发布。订阅房间主题必须绑定当前用户与房间权限，用户私有队列只允许本人规范地址；撤销会话时关闭相应连接。Spring 官方文档明确区分 MESSAGE 与 SUBSCRIBE 的授权，并说明允许客户端向 broker 主题发布会导致冒充系统消息：[WebSocket Security](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html)。

**验收：** 两用户、两个房间覆盖成员订阅成功/非成员失败；直接 broker SEND 被拒绝且其他客户端收不到伪造数据；禁止直接访问底层用户队列；会话撤销和到期后现有连接不能继续读写。测试应同时覆盖原生 WebSocket 与 SockJS。

### SEC-002 — 普通用户注销不撤销，令牌未绑定原始 SSO 会话（高）

**位置：** `frontend/src/hooks/useAuth.tsx:111`；`backend/src/main/java/com/aisocialgame/controller/AuthController.java:36`；`backend/src/main/java/com/aisocialgame/service/AuthService.java:120`、`:220`、`:226`；`backend/src/main/java/com/aisocialgame/service/token/RedisTokenStore.java:23`；`backend/src/main/resources/application.yml:80`。

**调用链与条件：** 浏览器 logout 仅删除 sessionStorage 并清本地状态，没有调用后端撤销。签发令牌只保存 `token → userId`；每次认证再从 `User` 实体读取当前 `sessionId`。用户再次 SSO 登录时覆盖同一用户行的 sessionId，而旧 token 仍指向同一个用户。默认本地令牌 TTL 为 168 小时，实际运行值可覆盖。

**影响：** 已获得令牌的第三方在用户点击退出后仍可操作账户。即使旧 SSO 会话被撤销，只要旧本地 token 未到期且尚未因一次失败认证而删除，用户新登录写入有效会话后，该旧 token 可以借用新会话通过认证。多设备令牌失去各自会话边界。UUID 随机性本身不是本项问题，也没有发现无需令牌的直接 HTTP 认证绕过。

**建议：** TokenStore 保存独立会话记录，绑定签发时 SSO sessionId、用户、过期时间和撤销状态；认证时检查该记录，不从共享用户资料取“最新会话”。增加幂等注销接口并撤销当前本地 token，需要时提供全设备注销；前端注销等待/处理后端结果，并关闭 WebSocket。

**验收：** 同一账户签发 A/B 两令牌并绑定不同会话；注销 A 后 A 的 HTTP/WS 均失败，B 按产品策略保留或同时撤销；撤销旧上游会话后再次登录，A 不得恢复。增加 TTL 边界、重复注销及并发登录测试。

### SEC-003 — AI 费用在项目积分准入之前发生，OCR 漏记使用量（高）

**位置：** `backend/src/main/java/com/aisocialgame/service/AiProxyService.java:80`、`:116`、`:136`、`:147`、`:151`；`backend/src/main/java/com/aisocialgame/service/ProjectCreditService.java:348`、`:366`；`backend/src/main/java/com/aisocialgame/integration/grpc/client/AiGrpcClient.java:174`；`backend/src/main/proto/ai/v1/ai_service.proto:96`；`backend/src/main/java/com/aisocialgame/integration/grpc/dto/AiOcrResult.java:3`。

**调用链与条件：** 已登录 `/api/ai/chat`、`/embeddings` → 先请求上游产生结果 → `applyConsume` 才检查/扣减项目积分。余额不足会在上游成功后抛错；余额为零也未在该调用链进入上游前拒绝。`ocrParse` 直接返回 RPC 结果，不调用项目积分服务；本地 OCR proto/result 不含 usage，`admission.finish` 传入 null usage。

**影响边界：** 当上游允许请求且项目承担这部分费用时，可造成已发生的上游成本无法与本地项目账本一致结算；OCR 即使成功也不进入同类项目消费流程。项目/user 请求速率限制已存在，不能将本问题描述成完全无限调用；上游自身计费、公共积分和配额是否另行阻断，需独立验证。规范 ai-service 契约已给出 OCR 成功的权威 `prompt_tokens/completion_tokens/total_tokens`，本地协议未跟进，不能以缺少字段认定 OCR 免费。

**建议：** 明确各 AI 功能的计费策略，在调用前进行原子预算预留，成功按权威 usage 结算，失败释放；稳定 requestId 贯穿上游、预留、结算及重试。同步 canonical OCR 协议并保留 usage；如 OCR 是明确补贴功能，也需项目预算、可观测计量和硬上限。外部超时不应直接视为未计费，需对账机制。

**验收：** Mock 上游，零余额 chat/embedding 在 RPC 前拒绝；并发请求不能超额预留；超时、上游已成功而本地提交失败、重试均不漏扣或重复扣；OCR 成功的权威 usage 可在项目审计/账本中追踪。验收不必产生真实费用。

### SEC-004 — 匿名社区写入缺少稳定主体约束，标签集合无界（中）

**位置：** `backend/src/main/java/com/aisocialgame/controller/CommunityController.java:30`、`:39`；`backend/src/main/java/com/aisocialgame/service/CommunityService.java:36`、`:45`、`:59`；`backend/src/main/java/com/aisocialgame/dto/CommunityPostRequest.java:13`；`backend/src/main/java/com/aisocialgame/service/safety/AiSafetyService.java:249`。

**事实与条件：** 发帖允许匿名，作者治理键来自任意 `X-Guest-Name`；内容审查是词条/隐私规则，并非单位时间发帖限额。点赞接口无认证，每次直接 `likes + 1`。content 限 1024 字符，但 tags 没有数量、单项长度或内容限制，落入 LONGTEXT。请求可经直连客户端发出，浏览器 CORS 不构成保护。

**影响：** 重复点赞污染指标；改变游客名可以改变治理主体；批量合规短文本或大标签可消耗数据库并淹没社区内容。是否有运行网关 body/rate 限额未知，不能把未知控制当已存在，也不能声称公网无任何限制。

**建议：** 保留游客功能时为游客签发稳定匿名会话，在用户/匿名会话、IP 与全局层分别限额；点赞以主体和帖子唯一约束幂等计数；限制 tags 数量/单项长度及字符集，统一请求体上限，对所有公开显示字段应用合理审查。

**验收：** 同一主体多次点赞不增加计数；更换昵称不能绕过发帖阈值；超量/超长 tags 返回 400/413 且数据库不落库；验证相应 429 指标，不依赖 UI 按钮禁用。

### SEC-005 — 房间口令猜测无限流并占用数据库排他锁（中）

**位置：** `backend/src/main/java/com/aisocialgame/service/RoomService.java:32`、`:109`、`:170`、`:175`、`:185`；`backend/src/main/java/com/aisocialgame/repository/RoomRepository.java:25`。

**事实与条件：** 加入房间先取得 `findByIdForUpdate` 的悲观写锁，再执行 BCrypt 口令检查。口令最低 4 字符；应用路径没有失败次数、用户/房间/IP 限频。需要普通已登录账户及私密房间 ID。

**影响：** 弱口令可被在线持续猜测；重复错误密码消耗 BCrypt CPU，并持有该房间排他锁阻塞合法 join/addAi 等写操作。BCrypt 是已存在的正确存储防护，但无法替代在线尝试约束。本项没有实际测得吞吐或实施爆破。

**建议：** 在昂贵哈希和获取写锁前做分层限速/退避；认证口令后只在座位分配提交阶段短暂持锁，并重新验证必要状态，避免 TOCTOU；对弱口令提供更强规则或邀请令牌。

**验收：** 错误尝试达到阈值即返回 429，且不进入 BCrypt/房间写锁；同房间合法操作在受控错误请求期间仍有有界延迟；并发正确加入不能重复分配或超员。

### SEC-006 — SSO HTTPS 的回环证书信任绕过（中）

**位置：** `backend/src/main/java/com/aisocialgame/service/AuthService.java:161`、`:172`、`:181`。

**事实与条件：** 正常 HttpClient 发生任意 IOException 后，若 endpoint 为 `https://localhost` 或 `https://127.0.0.1`，请求会由 `checkServerTrusted` 空实现的客户端重发；没有 ENV/profile 限制。它跳过证书链信任验证，代码没有显式关闭 hostname 验证，不能夸大成所有主机检查都被关闭。当前配置默认是具名服务域名，因此并非默认一定触发。

**影响：** 当运行配置采用回环 HTTPS 且本机端口被错误服务/恶意本机进程占据或信任配置有误时，SSO 授权码及令牌交换信任边界降低；其他 IOException 也会重试一次一次性授权码，造成结果歧义。没有证据表明本机已有恶意进程。

**建议：** 删除 trust-all 回退，使用已登记 CA/truststore 并保留主机名验证；TLS 失败终止交换，传输重试须遵从授权码单次消费契约。

**验收：** 自签且不受信任、错误主机名、过期证书均失败且不触发第二次不安全交换；受信任本地 CA 正常通过。对 local/test/production 均验证，不能只依赖默认域名。

### SEC-007 — 前端依赖公告与 pnpm 审计覆盖缺口（中）

**位置：** `frontend/pnpm-lock.yaml:2095`、`:2151`、`:2272`、`:2315`、`:2630`、`:2696`；`scripts/ci/build-release.sh:4`；`scripts/ci/aienie-ci-phase.sh:348`、`:368`、`:408`。

**结果：** 2026-09-26 `pnpm audit --json` 返回 **20 条 advisory 记录：8 high、11 moderate、1 low、0 critical，去重为 17 个 GHSA、13 个包名**。这不是 20 个独立漏洞或 20 个可利用入口。扫描结果为当前锁图，包括开发依赖和传递依赖。

| 锁定组件 | 主要公告及作用面 | 修复判断 |
| --- | --- | --- |
| glob 10.4.5 | CLI `-c/--cmd` 对恶意文件名执行 shell；构建工具链 | 官方修复线 10.5.0+，并检查是否实际调用 CLI cmd 功能 |
| rollup 4.40.0 | 构建输出路径穿越/任意文件写入；依赖恶意构建输入 | 官方修复线 4.59.0+ |
| flatted 3.3.3 | parse 递归 DoS 与原型污染；经 ESLint 缓存链引入 | 扫描建议 3.4.2+；上游第一则公告 patched 字段仍为 None，需同时复核变更及重扫 |
| picomatch 2.3.1 / 4.0.2 | extglob ReDoS；主要是 glob/构建路径 | 扫描建议 2.3.2+ / 4.0.4+，本次供应商直达页未能读取，保留扫描证据而不伪称官方复核通过 |
| lodash 4.17.21 | template imports 键名代码注入；经 recharts 引入 | registry 报告为 4.17.24+，供应商公告要求 4.18.0；采用供应商说明并重新审计 |
| js-yaml 4.3.1 | 空 merge source 可绕过 CPU 工作量限制；工具链 YAML 输入 | 扫描修复线 4.3.2+，升级后复扫 |
| react-router / react-router-dom 6.30.4 | 不可信路径重定向、SSR hydration 等公告 | 当前为 Vite SPA，SSR 场景未见适用；迁移/补丁需结合实际路由与官方版本支持验证 |

没有发现业务代码把任意远端输入交给 lodash.template；不能由该依赖存在直接推出浏览器 RCE。工具链问题需有可控文件、模式或缓存等前提。`pnpm-workspace.yaml` 已限制可运行的构建脚本到声明项，这是正向防护。

CI 中 npm audit 仅遍历 `AIENIE_CI_NPM_MODULES`，本项目该数组为空而 frontend 在 PNPM_MODULES；pnpm resolve 仅 fetch。manifest 的 `npm_audit` 又按开关生成状态，开启开关仍不代表 frontend 已审计。外部统一 CI 是否另有扫描未验证。

**建议：** 在 pnpm 的 resolve 阶段增加基于锁图的漏洞审计及报告归档；按可达性处理有期限的例外，避免将空 npm 模块扫描写成安全通过；升级受影响传递链时保留 frozen lock、运行构建和相关测试。

**验收：** 本锁文件能使新门禁给出明确失败/受控例外；升级后重新审计且核验真实 resolved 版本；报告区分 dev/runtime、独立 GHSA 与包实例，不能只用生产扫描省略开发供应链风险。

官方核验来源（2026-09-26）：[glob](https://github.com/isaacs/node-glob/security/advisories/GHSA-5j98-mcp5-4vw2)、[Rollup](https://github.com/rollup/rollup/security/advisories/GHSA-mw96-cpmx-2vgc)、[flatted DoS](https://github.com/WebReflection/flatted/security/advisories/GHSA-25h7-pfq9-p65f)、[flatted 原型污染](https://github.com/WebReflection/flatted/security/advisories/GHSA-rf6f-7fwh-wjgh)、[lodash](https://github.com/lodash/lodash/security/advisories/GHSA-r5fr-rjxr-66jc)、[js-yaml](https://github.com/nodeca/js-yaml/security/advisories/GHSA-2883-xcg3-v3hh)、[React Router](https://github.com/remix-run/react-router/security/advisories/GHSA-wrjc-x8rr-h8h6)。其余条目完整编号和版本以脱敏 JSON 为本次扫描快照，不能将建议版本视为未来永远安全。

### SEC-008 — protobuf-java 实际依赖存在已知递归 DoS（中）

**位置：** `backend/pom.xml:14`、`:16`、`:77`、`:151`；本报告附件 `security-maven-dependencies.txt:5`。

**事实：** 当前离线 Maven 解析链为 `grpc-protobuf:1.63.0 → protobuf-java:3.25.1`。pom 的 `protobuf.version=3.25.3` 用于 protoc 生成器，不能当作实际 Java runtime 版本。官方 [GHSA-735f-pc8j-v9w8 / CVE-2024-7254](https://github.com/protocolbuffers/protobuf/security/advisories/GHSA-735f-pc8j-v9w8) 说明恶意嵌套未知 group 可触发栈溢出 DoS；3.25 分支修复为 3.25.5。

**影响条件：** 本项目以 gRPC 客户端消费公共服务响应，尚未发现把原始不可信 protobuf 作为公网 HTTP 输入的入口；需恶意/异常响应到达受影响解析器。上游公告标高，本项目因输入边界按中评估，而非把依赖存在等同公网匿名崩溃漏洞。

**建议：** 协调 gRPC/protobuf BOM、生成器与运行库版本，显式管理兼容且不受本公告影响的 runtime；执行全量 Maven SCA，检查 grpc-netty-shaded 等传递链，不能仅升级 protoc。

**验收：** 重新输出 runtime dependency tree/SBOM，确认实际 Java runtime 已移出受影响区间；离线恶意嵌套解析测试安全失败；所有公共服务协议回归通过，并保留整树漏洞扫描结果。

## 4. 已有有效防护与未定性事项

| 范围 | 本次确认与保留意见 |
| --- | --- |
| SSO 与 HTTP 身份 | 回调通过服务端交换 code，再经 user-service `validateSession`；需要身份的业务入口使用 `@CurrentUser`；前端校验一次性 sessionStorage state。没有把“state 在前端校验”直接定性为可用的登录 CSRF。 |
| 管理员 | BCrypt、随机 session、token 哈希存储、TOTP AES-GCM 加密、挑战及时间步消费、受限恢复会话、Redis 限速；Cookie 有 HttpOnly/SameSite=Strict，非 local 强制 Secure；高风险操作 proof 绑定 method/URI/query/body 且单次消费。相关代码与现有测试已阅读，未重新执行管理员在线登录。 |
| 游戏与回放 | addAi/start 检查房主；V2 action 从认证 User 派生 actor；回放 PLAYER 视角校验当前用户参与记录，拒绝 GOD 与伪造 viewerPlayerId；未将公开 PUBLIC 回放当越权。私密房间元数据/结束后回放是否也应私密属于产品隐私契约待确认。 |
| 数据泄漏 | User password/sessionId/accessToken 均有 JsonIgnore；RoomResponse 不返回房间密码，并对白名单配置投影。User.accessToken 仍明文落库，主代码除 getter 外未见消费，建议按最小化原则移除无用途 token 或加密；本次未确认数据库访问失陷，未把它算成现成凭据泄露。 |
| 注入与反序列化 | 抽查 JPA/Repository、AdminAuthStore JDBC 参数及 JSON 转换器，未发现从请求拼接执行 SQL/OS 命令的可达链。JSON 采用指定类型/Map，并未发现基于不可信类型名的默认多态配置。未作所有依赖解析器的穷尽证明。 |
| 前端 XSS | 业务内容主要由 React 文本节点渲染；发现的 chart `dangerouslySetInnerHTML` 用于样式生成，本次未找到不可信业务数据进入 ChartConfig 的链，未报存储 XSS。浏览器 token 在 sessionStorage 仍受同源 XSS 影响，需要持续输出编码及 CSP 防护。 |
| 凭据与日志 | 工作区秘密模式扫描未发现所检模式的实际私钥/API token；配置使用注入，通用异常返回抽象错误，日志有 SafeLogThrowable。未扫描完整 Git 历史、忽略的真实运行机密、构建缓存或生产日志；扫描无命中不等于仓库绝无秘密。 |
| 传输与运行 | 项目有 production MySQL VERIFY_IDENTITY、Redis TLS/auth、gRPC 传输及身份启动校验。test 数据传输存在治理文档已重定基线的明文例外，未擅自把它当生产现状。没有验证外部网关的 CSP/HSTS、body/rate limit、metrics ACL 或网络隔离。 |
| SSRF | OCR image/document URL 本地仅限 http(s) 并转给 ai-service，最终下载/重定向/DNS 地址过滤边界位于外部服务；本次未验证该边界，不能仅凭 URL 正则就确认或排除 SSRF。 |
| 脚本/供应链 | 阅读 release 入口、CI 依赖解析路径和运行安全校验，并搜索证书绕过/命令执行模式。外置 Jenkins helper、配置中心、实际构建镜像和运行主机权限不在本地代码证明范围内。 |

## 5. 修复顺序与复审标准

先完成 SEC-001/002 的通道授权和会话隔离，再完成 SEC-003 的项目预算预留与 OCR 计量。随后处理匿名写入、口令限速和 SSO TLS 回退；依赖升级及 pnpm/Maven SCA 门禁可以并行推进。

每项关闭均需保存对应负向回归、实际 resolved 依赖版本或策略验证证据。普通单元测试通过不替代跨用户订阅/会话撤销测试；本地静态代码证据不替代目标环境入口验证。对于外部防护能够降低风险的项，复审应给出具体配置、环境与验证时间，不能只记录“内网服务”或“已有网关”。
