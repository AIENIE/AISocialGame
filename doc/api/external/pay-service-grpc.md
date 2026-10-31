# pay-service gRPC 接口（外部依赖）

> 更新时间：2026-05-23

## 服务地址与服务名

- 本地默认地址：`BILLING_GRPC_ADDR=static://localpayservice.testhut.top:22021`
- 默认传输：`BILLING_GRPC_NEGOTIATION_TYPE=TLS`
- 当前发现方式：静态域名/端口，不使用 Consul。
- gRPC 服务：
  - `fireflychat.billing.v1.BillingBalanceService`
  - `fireflychat.billing.v1.BillingConversionService`
  - `fireflychat.billing.v1.BillingCheckinService`
  - `fireflychat.billing.v1.BillingRedeemCodeService`
  - `fireflychat.billing.v1.BillingQueryService`
  - `fireflychat.billing.v1.BillingOnboardingService`

## 本项目使用的方法

- `GetPublicBalance`
- `ConvertPublicToProject`
- `EnsureUserInitialized`

## 鉴权要求

pay-service 业务 gRPC 请求需携带：

- `authorization: Bearer <service_jwt>`

该 token 必须满足 pay-service 的 `issuer/audience/role/scopes` 约束。
AISocialGame 当前需要的细粒度 scopes 为：`billing.balance.read`、`billing.balance.convert`、`billing.onboarding.write`。
本项目由 `BillingGrpcAuthClientInterceptor` 自动注入该 header。

## AISocialGame 使用边界

pay-service 只承接 onboarding、公共余额、公共转项目。AISocialGame 的项目专属余额、签到、兑换码、AI 调用扣费和账本仍保留在本地数据库。

当前 pay-service 契约以 `credits` 为主字段；AISocialGame 使用这些字段，不读取或写入废弃的 `*_tokens` 镜像。
