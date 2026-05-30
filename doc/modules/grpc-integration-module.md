# gRPC 集成模块说明（v1.7）

> 更新时间：2026-05-23

## 目标

对接 user-service、pay-service、ai-service 三个外部服务，并通过静态域名地址建立 gRPC 通道。当前版本不使用 Consul 服务发现。

## 组成

- `integration/grpc/client/UserGrpcClient`
  - 会话校验、用户信息、封禁状态
- `integration/grpc/client/BillingGrpcClient`
  - 通用积分读取、通用转专属、项目积分迁移、onboarding 初始化
- `integration/grpc/client/AiGrpcClient`
  - 模型列表、对话、embeddings、ocr；proto 保留 `GenerateImage` 契约但 AISocialGame 当前不新增生图业务入口
- `integration/grpc/auth/UserGrpcAuthClientInterceptor`
  - 自动注入 `x-internal-token`
- `integration/grpc/auth/BillingGrpcAuthClientInterceptor`
  - 自动注入 `authorization: Bearer <service_jwt>`
- `integration/grpc/auth/AiGrpcHmacClientInterceptor`
  - 自动注入 `x-aienie-*` HMAC metadata
## 当前配置策略

- 地址：
  - `USER_GRPC_ADDR=static://userservice.localhut.com:10001`
  - `BILLING_GRPC_ADDR=static://payservice.localhut.com:10021`
  - `AI_GRPC_ADDR=static://aiservice.localhut.com:10011`
- SSO HTTP 入口：
  - `SSO_USER_SERVICE_BASE_URL=https://userservice.localhut.com`
- 传输：
  - 默认 `USER_GRPC_NEGOTIATION_TYPE=PLAINTEXT`
  - 默认 `BILLING_GRPC_NEGOTIATION_TYPE=PLAINTEXT`，与 `aienie-doc/pay-service/01-完整对接.md` 的 `grpcurl -plaintext` 和 `10021` 对齐
  - 默认 `AI_GRPC_NEGOTIATION_TYPE=PLAINTEXT`
  - `RuntimeSecurityValidator` 仍保留明文拦截能力；当任一 gRPC 通道为 `PLAINTEXT` 时，必须允许 `APP_SECURITY_ALLOW_PLAINTEXT_GRPC=true`，否则启动期拒绝运行。

## aienie 三服务契约边界

- `user-service`：AISocialGame 使用会话校验、用户基础信息、封禁状态等身份能力；本地 `User` 仍保存 AISocialGame 的登录映射、昵称、会话和项目内状态。
- `ai-service`：`ListModels` 必须携带真实 user-service 用户 ID；普通 `/api/ai/models` 从当前登录用户取 `externalUserId`，管理端模型统计和联通性诊断使用显式配置的 `APP_AI_SYSTEM_USER_ID`，未配置时失败而不是发送空请求。对话、Embeddings、OCR 继续传 `project_key/user_id/session_id/model`，字段按当前 proto 对齐。
- `pay-service`：只承担 onboarding、公共余额读取、公共转项目、迁移快照。AISocialGame 本地项目钱包、签到、兑换码、AI 成功调用扣费和项目账本继续保留在本项目数据库。
- pay-service 当前以 `credits` 为主字段，`tokens` 仅作兼容镜像。AISocialGame 读取响应时优先 `*_credits`，写入 `ConvertPublicToProject` 时同时传 `credits` 和兼容 `tokens`。
- 公共转项目的 `request_id` 由前端生成并在失败重试时复用；后端拒绝空 `requestId`，避免空请求被重新随机生成而造成重复扣公共余额。

## 鉴权约束（严格）

默认 `APP_EXTERNAL_GRPC_AUTH_REQUIRED=true`，并要求：

- `APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN`
- `APP_EXTERNAL_PAYSERVICE_JWT`
- `APP_EXTERNAL_AISERVICE_HMAC_CALLER`
- `APP_EXTERNAL_AISERVICE_HMAC_SECRET`

启动期由 `ExternalGrpcAuthValidator` 进行 fail-fast 校验，缺失即拒绝启动。

### pay-service JWT 时效要求

- `APP_EXTERNAL_PAYSERVICE_JWT` 为服务间 Bearer JWT，必须包含：
  - `iss=aienie-services`
  - `aud=aienie-payservice-grpc`
  - `role=SERVICE`
  - `scopes` 至少包含 `billing.read`、`billing.write`
- 若该 JWT 过期，会在 SSO 回调阶段触发 pay-service onboarding 调用失败，外显为：
  - `POST /api/auth/sso-callback` 返回 `401`
  - 响应消息：`Invalid token`
- 部署前建议重新签发该 JWT 并注入环境变量，再执行 `build.sh`。

## 运行链路

1. 启动后基于 `grpc.client.*.address` 建立三服务 gRPC 通道。
2. 每次调用由对应拦截器自动注入鉴权 metadata。
3. SSO 回调中调用 user-service 校验会话。
4. 首次登录调用 pay-service onboarding + 本地账户初始化。
5. 公共转项目调用 pay-service 扣公共余额，并在本地账本落地专属积分入账流水。
6. AI 请求成功后仅在本地项目账本落地 `CONSUME` 流水。
