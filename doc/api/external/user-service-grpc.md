# user-service gRPC 接口（外部依赖）

> 更新时间：2026-02-24

## 服务地址与服务名

- 本地默认地址：`USER_GRPC_ADDR=static://localuserservice.testhut.top:22001`
- 默认传输：`USER_GRPC_NEGOTIATION_TYPE=TLS`
- 当前发现方式：静态域名/端口，不使用 Consul。
- gRPC 服务：
  - `fireflychat.user.v1.UserAuthService`
  - `fireflychat.user.v1.UserDirectoryService`
  - `fireflychat.user.v1.UserBanService`

## 本项目使用的方法

- `UserAuthService/ValidateSession`
- `UserDirectoryService/GetUserBasic`
- `UserBanService/GetBanStatus`
- `UserBanService/BanUser`
- `UserBanService/UnbanUser`

## 鉴权要求

除少数公开方法外，user-service 受保护方法需在 `authorization: Bearer <JWT>` 携带调用方短期 HS256 JWT；旧 `x-internal-token` 不再接受。
AISocialGame 的 `iss/sub` 固定为 `aisocialgame`，`aud` 固定为 `aienie-userservice-grpc`，TTL 为
300 秒，并只申请 `user.auth.session.read`、`user.directory.read`、`user.ban.read`、
`user.ban.write`。JWT 包含 `iat/nbf/exp/jti/scopes`，由 `UserGrpcAuthClientInterceptor` 在每次真实
调用开始时获取并注入；旧静态 token 不再接受。
