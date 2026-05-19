# gRPC 集成模块说明（v1.6）

> 更新时间：2026-05-19

## 目标

对接 user-service、pay-service、ai-service 三个外部服务，并通过静态域名地址建立 gRPC 通道。当前版本不使用 Consul 服务发现。

## 组成

- `integration/grpc/client/UserGrpcClient`
  - 会话校验、用户信息、封禁状态
- `integration/grpc/client/BillingGrpcClient`
  - 通用积分读取、通用转专属、项目积分迁移、onboarding 初始化
- `integration/grpc/client/AiGrpcClient`
  - 模型列表、对话、embeddings、ocr
- `integration/grpc/auth/UserGrpcAuthClientInterceptor`
  - 自动注入 `x-internal-token`
- `integration/grpc/auth/BillingGrpcAuthClientInterceptor`
  - 自动注入 `authorization: Bearer <service_jwt>`
- `integration/grpc/auth/AiGrpcHmacClientInterceptor`
  - 自动注入 `x-aienie-*` HMAC metadata
## 当前配置策略

- 地址：
  - `USER_GRPC_ADDR=static://userservice.seekerhut.com:10001`
  - `BILLING_GRPC_ADDR=static://payservice.seekerhut.com:20021`
  - `AI_GRPC_ADDR=static://aiservice.seekerhut.com:10011`
- SSO HTTP 入口：
  - `SSO_USER_SERVICE_BASE_URL=https://userservice.seekerhut.com`
- 传输：
  - 默认 `USER_GRPC_NEGOTIATION_TYPE=TLS`
  - 默认 `BILLING_GRPC_NEGOTIATION_TYPE=TLS`
  - 默认 `AI_GRPC_NEGOTIATION_TYPE=TLS`
  - 如外部服务短期只能明文，必须显式设置 `APP_SECURITY_ALLOW_PLAINTEXT_GRPC=true` 并记录网络隔离前提。

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
- 部署前建议重新签发该 JWT 并注入环境变量，再执行 `build.sh`/`build_prod.sh`。

## 运行链路

1. 启动后基于 `grpc.client.*.address` 建立三服务 gRPC 通道。
2. 每次调用由对应拦截器自动注入鉴权 metadata。
3. SSO 回调中调用 user-service 校验会话。
4. 首次登录调用 pay-service onboarding + 本地账户初始化。
5. 钱包兑换调用 pay-service 并在本地账本落地流水。
6. AI 请求成功后在本地落地 `CONSUME` 流水。
