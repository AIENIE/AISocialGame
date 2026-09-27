# 安全加固模块说明

## 模块目标

本模块记录 v1.0 安全审计后的代码侧整改结果，覆盖登录边界、房间权限、实时通信、AI 调用、异常响应和运行配置。

## 登录与玩家身份

- 房间创建、加入、开局、发言、投票、夜晚行动、观战状态查询、WebSocket 连接和 AI 对话均要求 `X-Auth-Token`。
- 后端玩家身份只取登录用户 ID，不再接受客户端传入的玩家 ID 作为身份凭据。
- 前端仍可在响应中读取 `selfPlayerId` 用于展示和兼容旧 UI 状态，但不再把它作为请求鉴权来源。

## 房间权限

- 私密房创建时必须提供 4-64 位密码，后端使用 BCrypt 存储，不保存明文。
- 加入私密房必须提交 `password`，密码错误返回 `403`。
- 添加 AI 只允许房主在 `WAITING` 房间执行，非房主和未登录请求分别返回 `403` 与 `401`。

## WebSocket 与 CORS

- STOMP CONNECT 只接受 `Authorization: Bearer <token>`，认证成功后 Principal 绑定为登录用户 ID。
- WebSocket origin 与 HTTP CORS 共用 `app.cors.allowed-origins` 白名单，不再允许任意 origin。
- HTTP CORS 只允许精确来源及必要 header；管理员仅额外允许一次性 `X-Admin-Operation-Proof`。

## AI 接口保护

- `/api/ai/models` 保持公开。
- `/api/ai/chat`、`/api/ai/chat/stream`、`/api/ai/embeddings`、`/api/ai/ocr` 均要求登录。
- Chat 请求限制最多 20 条消息，单条 role 最多 32 字符，content 最多 4000 字符。

## 运行配置

- `env.example` 是无敏感值模板；本地调试的真实值只放在权限为 `0600` 且未入库的 `env.local`（VS Code F5）或 `%LOCALAPPDATA%\Aienie\secrets\aisocialgame.env`（Windows 调试脚本）中，发版环境的运行时配置由 config-center 在部署侧注入。
- 非本地环境启动时会 fail-fast 校验；`ENV=test` 的固定 MySQL 入口要求 `sslMode=REQUIRED&allowPublicKeyRetrieval=false`，`ENV=production` 仍要求 `VERIFY_IDENTITY&allowPublicKeyRetrieval=false`。本地隔离调试可使用独立的明文夹具。其余校验包括：
  - `APP_ADMIN_PASSWORD_HASH` 必须为 BCrypt 摘要，`SPRING_DATASOURCE_PASSWORD` 必须存在。
  - 管理认证只接受严格 OS `ENV`/`AUTH_MODE` 四种组合；TOTP 模式必须配置有效 32 字节版本化 keyring。
  - 不允许默认弱口令。
  - 不允许 MySQL URL 禁用 TLS 或启用 public key retrieval。
  - 不允许 gRPC `PLAINTEXT`，除非显式设置 `APP_SECURITY_ALLOW_PLAINTEXT_GRPC=true`。
- 如果外部服务短期只能明文 gRPC，必须在部署说明中记录网络隔离前提，并只在受控环境临时开启。

## 异常响应

- `ApiException` 继续返回业务错误消息。
- JSON 解析错误只返回固定格式错误。
- 未捕获异常只返回 `服务器内部错误`，内部异常堆栈写入服务端日志。

## 部署前外部动作

- 先幂等执行并核验 `backend/sql/20260810_admin_totp_auth.sql`，确认管理员认证表已创建。
- 轮换 user-service 内部 token、pay-service JWT、ai-service HMAC caller/secret。
- 将新值注入 `env.local`，设置权限 `0600`，不写入仓库。
- 确认 MySQL 与三条 gRPC 链路支持 TLS；如暂不支持，记录隔离范围和临时放行变量。
