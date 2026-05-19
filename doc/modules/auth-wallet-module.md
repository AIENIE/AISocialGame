# 认证与钱包模块说明（v1.6）

> 更新时间：2026-05-19

## 模块职责

- 认证：统一通过 user-service SSO 登录/注册，本项目仅负责回调校验与本地会话建立。
- 钱包：项目专属积分由本地账本维护，通用积分通过 pay-service 读取与兑换。
- 扣费：AI 调用成功后，仅扣减本地项目专属积分（临时优先，永久补足）。

## 关键实现

- 后端
  - `AuthController` / `AuthService`
    - `GET /api/auth/sso/login`
    - `GET /api/auth/sso/register`
    - `POST /api/auth/sso-callback`
    - `GET /api/auth/me`
  - `WalletController` / `WalletService`
    - `GET/POST /api/wallet/*`
- `ProjectCreditService`
    - 本地账户初始化、签到、兑换码、账本、通用转专属、消耗流水
    - 通用转专属采用 PENDING -> pay-service -> SUCCESS/FAILED 状态机，远程 gRPC 不在本地数据库事务内执行。
- 前端
  - `useAuth`：生成一次性 `state` 并跳转 SSO
  - `SsoCallback`：校验 `state` 并调用后端回调
  - `Profile` + `WalletPanel`：余额、兑换、历史记录

## SSO 地址解析策略

`AuthService` 的 user-service 地址解析顺序：

1. 优先 `app.sso.user-service-base-url`（默认 `https://userservice.seekerhut.com`）
2. 未配置时回退 Consul HTTP 服务发现（`app.sso.user-service-name`）

## 首次登录初始化

`POST /api/auth/sso-callback` 在首次登录时执行：

- `billingGrpcClient.ensureUserInitialized(...)`
- `projectCreditService.ensureAccountInitialized(...)`

确保首次登录用户可直接使用钱包与业务能力。

## 钱包能力清单

- 每日签到：`POST /api/wallet/checkin`
- 签到状态：`GET /api/wallet/checkin-status`
- 余额：`GET /api/wallet/balance`
- 消耗记录：`GET /api/wallet/usage-records`
- 本地账本：`GET /api/wallet/ledger`
- 兑换码：`POST /api/wallet/redeem`
- 通用转专属：`POST /api/wallet/exchange/public-to-project`
- 兑换码历史：`GET /api/wallet/redemption-history`
- 通用转专属历史（含兑换前后余额）：`GET /api/wallet/exchange-history`

## 登录边界

- 本项目不提供本地账号密码注册/登录页面。
- 用户所有注册登录流程均在 user-service SSO 页面完成。
- 对局、观战状态、WebSocket 和 AI 调用均要求登录用户身份。
- 前端用户 token 与管理员 token 使用 `sessionStorage`，降低跨会话持久化暴露风险。

## 管理员 session

- 非 test 环境使用 Redis TTL 存储管理员 session，key 前缀为 `<projectKey>:admin:session:`。
- test/无 Redis 构造路径使用内存 Map，并由定时任务清理过期 token。
