# AISocialGame v1.0 代码安全审计

- 审计日期：2026-05-19
- 审计范围：Spring Boot 后端、React/Vite 前端、部署脚本、运行配置、SQL 与既有文档
- 风险等级：严重 / 高 / 中 / 低 / 建议
- 说明：本文仅记录源码与配置层面的审计发现，不包含生产环境实测攻击。

## 总体结论

项目已具备 SSO、Redis token、外部 gRPC 鉴权、管理员 token、WebSocket 身份拦截、积分失败次数限制等基础控制，但仍存在几类高优先级安全风险：仓库中出现疑似真实服务凭据，默认弱口令和明文链路可被误带入部署，房间与 WebSocket 的游客身份模型允许较低成本的身份冒用，部分高价值操作缺少房主或登录校验。

建议先处理凭据轮换、默认口令、明文链路和房间权限，再补齐安全回归测试。

## 风险清单

| 等级 | 风险 | 证据 | 影响 |
| --- | --- | --- | --- |
| 严重 | 仓库配置包含外部服务 token/JWT | `env.txt:20-21` | 泄露后可伪造服务间调用或触发跨服务账务/用户接口访问 |
| 高 | 默认管理员密码与数据库密码可直接落入运行配置 | `backend/src/main/resources/application.yml:7-9`、`backend/src/main/resources/application.yml:48`、`docker-compose.yml:8`、`docker-compose.yml:19` | 环境变量缺失时暴露管理后台、数据库或测试环境 |
| 高 | 数据库和 gRPC 默认使用明文或弱传输 | `backend/src/main/resources/application.yml:7`、`backend/src/main/resources/application.yml:72-78` | 网络路径被监听或中间人攻击时，凭据、session、账务请求可被窃取或篡改 |
| 高 | WebSocket endpoint 允许任意 origin | `backend/src/main/java/com/aisocialgame/config/WebSocketConfig.java:29-30` | 任意站点可发起跨站 WebSocket 连接，配合 token/localStorage 风险扩大攻击面 |
| 高 | 私密房间密码只保存不校验 | `backend/src/main/java/com/aisocialgame/dto/CreateRoomRequest.java:8-13`、`backend/src/main/java/com/aisocialgame/service/RoomService.java:45-57`、`backend/src/main/java/com/aisocialgame/dto/JoinRoomRequest.java:5-7`、`backend/src/main/java/com/aisocialgame/service/RoomService.java:70-100` | 私密房间可被不知道密码的用户或游客加入 |
| 高 | 添加 AI 缺少登录/房主校验 | `backend/src/main/java/com/aisocialgame/controller/RoomController.java:68-70`、`backend/src/main/java/com/aisocialgame/service/RoomService.java:103-118` | 任意调用者可向房间加 AI，影响对局公平性与资源消耗 |
| 中 | 游客身份依赖客户端 `X-Player-Id` 回传 | `backend/src/main/java/com/aisocialgame/controller/RoomController.java:53-64`、`backend/src/main/java/com/aisocialgame/engine/GameRuntimeSupport.java:1145-1159`、`backend/src/main/java/com/aisocialgame/websocket/WebSocketAuthChannelInterceptor.java:30-47` | 获得或猜到玩家 ID 后可冒用游客席位执行对局动作或接收私有事件 |
| 中 | 未登录 `/api/ai/chat` 可走系统用户扣费身份 | `backend/src/main/java/com/aisocialgame/controller/AiController.java:47-50`、`backend/src/main/java/com/aisocialgame/service/AiProxyService.java:45-50` | 未登录请求可能消耗系统账户额度，且难以追踪到真实用户 |
| 中 | 前端 token 存储在 `localStorage` | `frontend/src/hooks/useAuth.tsx:42-64`、`frontend/src/hooks/useAdminAuth.tsx:171-198` | 一旦发生 XSS 或第三方脚本污染，用户与管理员 token 可被读取 |
| 中 | 通用异常将原始异常消息返回给客户端 | `GlobalExceptionHandler.java:55-60` | 数据库、gRPC、路径或内部状态可能通过错误消息泄露 |
| 中 | CORS 允许凭据且 headers 放开 | `WebConfig.java:15-34` | 与 token header、跨域前端组合时，需要严格控制 origin 与预检策略 |
| 低 | SSO 回调接受并保存 accessToken | `backend/src/main/java/com/aisocialgame/service/AuthService.java:81-99` | 若本地数据库或日志泄露，远端 access token 可能成为二次风险 |

## 重点发现

### 1. 服务凭据进入仓库

`env.txt` 中包含 `APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN` 和 `APP_EXTERNAL_PAYSERVICE_JWT`。即使当前 JWT 已过期，也应按泄露处理，因为它暴露了签发格式、issuer/audience/scope 等服务间鉴权结构。

建议：

- 立即轮换 user-service 内部 token、pay-service JWT、ai-service HMAC secret。
- 将 `env.txt` 改为无敏感值模板，真实值放入部署机受控 secret 文件或 CI/CD secret。
- 增加 secret 扫描规则，至少覆盖 JWT、`APP_EXTERNAL_*TOKEN`、`APP_EXTERNAL_*SECRET`、数据库密码。

验证：

```bash
rg -n "APP_EXTERNAL_.*(TOKEN|JWT|SECRET)|eyJhbGci|admin123|aisocialgame_pwd" .
```

### 2. 默认弱口令和明文链路

后端默认数据库 URL 使用 `useSSL=false&allowPublicKeyRetrieval=true`，管理员默认密码为 `admin123`，gRPC 客户端默认 `negotiationType: PLAINTEXT`。这些默认值在本地调试可接受，但部署脚本和 compose 同样继承这些兜底值，容易在测试服或正式服环境变量缺失时变成真实风险。

建议：

- 启动时校验生产/测试域名部署不得使用默认 `APP_ADMIN_PASSWORD`、`SPRING_DATASOURCE_PASSWORD`。
- 对 MySQL 开启 TLS，去除 `allowPublicKeyRetrieval=true` 默认值。
- gRPC 按服务能力迁移到 TLS 或内网 mTLS；短期至少要求走可信内网并记录网络隔离前提。

验证：

```bash
rg -n "admin123|aisocialgame_pwd|useSSL=false|allowPublicKeyRetrieval|PLAINTEXT" backend docker-compose.yml build_common.sh
```

### 3. 房间权限模型过宽

私密房间的 `password` 仅在创建时写入 `Room`，加入房间 DTO 不包含密码字段，`RoomService.joinRoom` 也未检查 `room.isPrivate()` 或密码。`/rooms/{roomId}/ai` 控制器没有读取用户 token，也没有检查调用者是否为房主。

建议：

- `JoinRoomRequest` 增加可选 `password`，私密房间必须校验。
- `addAi` 要求登录用户或已入座玩家身份，并调用统一的房主校验。
- 为游客席位发放服务端签名的 room-scoped seat token，替代裸 `X-Player-Id`。

验证：

```bash
curl -X POST "$BASE/api/games/undercover/rooms/$ROOM_ID/join" \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"guest-without-password"}'
```

期望私密房间返回 401/403 或 400，而不是成功入座。

### 4. WebSocket 与前端 token 风险叠加

WebSocket 允许 `setAllowedOriginPatterns("*")`，前端把用户 token 和管理员 token 放入 `localStorage`。React 默认会转义文本，当前只发现 shadcn chart 的样式注入使用 `dangerouslySetInnerHTML`，但一旦未来出现 XSS，`localStorage` token 会被直接读取。

建议：

- WebSocket allowed origins 与 `WebConfig` 保持同一域名白名单。
- 管理端 token 优先迁移到 HttpOnly/SameSite cookie 或短期内缩短 TTL、增加操作二次校验。
- 建立 XSS 检查规则：禁止业务内容使用 `dangerouslySetInnerHTML`，外链统一设置 `rel="noopener noreferrer"`。

验证：

```bash
rg -n "setAllowedOriginPatterns|localStorage|dangerouslySetInnerHTML|target=\"_blank\"" frontend backend
```

### 5. AI 与账务接口保护不足

`/api/ai/chat` 允许未登录调用并落到系统用户身份；流式 chat、embeddings、OCR 要求登录。此差异容易被滥用，且会让计费归因变差。

建议：

- `/api/ai/chat` 与其他 AI 接口保持一致，要求登录。
- 如果保留游客 AI，单独建立匿名配额、IP/设备限流和低成本模型白名单。
- 对 AI 请求增加消息数量、单条长度、总 token 预算校验。

## 安全测试建议

- 后端单测：私密房间无密码不能加入；非房主不能加 AI；游客 `X-Player-Id` 不能跨房间或跨用户复用。
- WebSocket 集成测试：非白名单 origin 连接失败；伪造 playerId 无法订阅/接收私有事件。
- 管理端测试：默认密码在非 local profile 下启动失败；管理员 token 过期后高危接口返回 401。
- 凭据扫描：CI 中加入 secret scan，阻止 JWT、私钥、固定服务 token 入库。
