# 安全加固模块说明

> 更新时间：2026-05-19

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
- HTTP CORS 只允许 `Content-Type`、`Authorization`、`X-Auth-Token`、`X-Admin-Token` 等必要 header。

## AI 接口保护

- `/api/ai/models` 保持公开。
- `/api/ai/chat`、`/api/ai/chat/stream`、`/api/ai/embeddings`、`/api/ai/ocr` 均要求登录。
- Chat 请求限制最多 20 条消息，单条 role 最多 32 字符，content 最多 4000 字符。

## 运行配置

- `env.txt` 是无敏感值模板，真实部署值应放在未入库的 `env.local` 或进程环境变量中。
- 非 test profile 启动时会 fail-fast 校验：
  - `APP_ADMIN_PASSWORD` 和 `SPRING_DATASOURCE_PASSWORD` 必须存在。
  - 不允许默认弱口令。
  - 不允许 MySQL URL 禁用 TLS 或启用 public key retrieval。
  - 不允许 gRPC `PLAINTEXT`，除非显式设置 `APP_SECURITY_ALLOW_PLAINTEXT_GRPC=true`。
- 如果外部服务短期只能明文 gRPC，必须在部署说明中记录网络隔离前提，并只在受控环境临时开启。

## 异常响应

- `ApiException` 继续返回业务错误消息。
- JSON 解析错误只返回固定格式错误。
- 未捕获异常只返回 `服务器内部错误`，内部异常堆栈写入服务端日志。

## 部署前外部动作

- 轮换 user-service 内部 token、pay-service JWT、ai-service HMAC caller/secret。
- 将新值注入 `env.local`、系统环境变量或 CI/CD secret，不写入仓库。
- 确认 MySQL 与三条 gRPC 链路支持 TLS；如暂不支持，记录隔离范围和临时放行变量。
