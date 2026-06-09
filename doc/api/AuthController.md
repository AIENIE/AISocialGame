# AuthController

基址：`/api/auth`

## 简介

- 职责：提供 SSO 跳转入口、SSO 回调换取本地会话、当前用户信息查询。
- 鉴权要求：
  - 无需本地鉴权：`/sso/login`、`/sso/register`、`/sso-callback`
  - 需要本地鉴权：`/me`（`X-Auth-Token`）

## 地址解析与回调策略

- SSO 登录页基地址取 `app.sso.user-service-base-url`（默认 `https://userservice.localhut.com`）。
- 当前版本不使用 Consul 服务发现；如果该配置缺失，后端会返回用户服务地址未配置。
- 回调地址由 `app.sso.callback-url` 控制，本地环境默认：
  - `https://aisocialgame.localhut.com/sso/callback`

## 接口列表

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/auth/sso/login` | 后端 302 跳转到 user-service 登录页 |
| GET | `/api/auth/sso/register` | 后端 302 跳转到 user-service 注册页 |
| POST | `/api/auth/sso-callback` | 处理 SSO 回调并建立本地会话 |
| GET | `/api/auth/me` | 获取当前登录用户信息 |

## 接口详情

### GET `/api/auth/sso/login`

- Query
  - `state` (String, required, 16~128 位，`[A-Za-z0-9_-]`)
- 返回
  - `302 Found`
  - `Location: https://userservice.localhut.com/sso/login?redirect=<callback>&state=<state>`
- 示例

```bash
curl -k -i "https://aisocialgame.localhut.com/api/auth/sso/login?state=1234567890abcdef1234567890abcdef"
```

### GET `/api/auth/sso/register`

- Query
  - `state` (String, required)
- 返回
  - `302 Found`
  - `Location: https://userservice.localhut.com/register?redirect=<callback>&state=<state>`
- 示例

```bash
curl -k -i "https://aisocialgame.localhut.com/api/auth/sso/register?state=1234567890abcdef1234567890abcdef"
```

### POST `/api/auth/sso-callback`

- 用途
  - 使用 user-service `code` 兑换外部会话
  - 建立/更新本地用户
  - 首次登录时初始化 pay-service + 本地专属积分账户
- Body
  - `code` (String, required)：user-service 回跳的一次性授权码
  - `redirect` (String, required)：发起 SSO 时传给 user-service 的原始 callback URL；前端只移除回跳追加的 `code/state`
- 成功响应
  - `token` (String)
  - `user` (AuthUserView)
- 示例

```bash
curl -k -X POST "https://aisocialgame.localhut.com/api/auth/sso-callback" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "userservice-code",
    "redirect": "https://aisocialgame.localhut.com/sso/callback"
  }'
```

### GET `/api/auth/me`

- Header
  - `X-Auth-Token: <token>`
- 返回
  - 当前用户基础信息 + 钱包聚合余额
- 示例

```bash
curl -k "https://aisocialgame.localhut.com/api/auth/me" \
  -H "X-Auth-Token: <token>"
```

## 常见错误码

- `400`：参数不合法（例如 `state` 格式不合法）
- `401`：未登录 / token 失效 / SSO 会话无效
- `503`：用户服务地址未配置或外部 user-service 不可用
