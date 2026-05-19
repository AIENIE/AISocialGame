# ai-service gRPC 接口（外部依赖）

> 更新时间：2026-02-24

## 服务地址与服务名

- 当前地址：`AI_GRPC_ADDR=static://aiservice.seekerhut.com:10011`
- 当前发现方式：静态域名/端口，不使用 Consul。
- gRPC 服务：`fireflychat.ai.v1.AiGatewayService`

## 本项目使用的方法

- `ListModels`
- `ChatCompletions`
- `Embeddings`
- `OcrParse`

## 鉴权要求

ai-service 默认要求 HMAC metadata：

- `x-aienie-caller`
- `x-aienie-ts`
- `x-aienie-nonce`
- `x-aienie-signature`

签名串规则：

`caller + "\n" + "/" + fullMethodName + "\n" + ts + "\n" + nonce`

本项目由 `AiGrpcHmacClientInterceptor` 自动计算并注入这些 headers。
