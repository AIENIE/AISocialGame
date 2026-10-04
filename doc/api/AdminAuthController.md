# AdminAuthController

基址：`/api/admin/auth`。所有响应均使用 `Cache-Control: no-store`，认证成功只设置
`AISOCIAL_ADMIN_SESSION`（`HttpOnly; Secure; SameSite=Strict`）cookie，不返回浏览器可持久化 token。

## 策略与登录

- `GET /policy`：返回严格 OS 环境变量 `ENV` / `AUTH_MODE` 解析后的策略与绑定状态。
- `POST /login`：先验证 BCrypt 管理员密码。`local/password` 直接建立会话；TOTP 模式返回 HTTP 202
  `TOTP_REQUIRED` 或 `ENROLLMENT_REQUIRED` challenge，验证 TOTP 前不会建立会话。
- `POST /totp/verify`：用密码阶段签发的 challenge 和 6 位 TOTP 完成登录。
- `POST /enrollment/start`、`POST /enrollment/confirm`：首次绑定。密钥只在 start 响应中出现，确认后以
  AES-256-GCM + 版本化 keyring 加密保存；首次确认生成并展示 10 个紧急码。
- `POST /recovery/verify`：恢复码只能在密码已验证的 challenge 上使用，成功立即消费紧急码，只建立最长 10 分钟的 `RECOVERY_REBIND_ONLY` 会话。
  该会话只能调用 `rebind/start`、`rebind/confirm`、`me` 和 `logout`；重绑 challenge 最长 120 秒、最多 5 次验证，并绑定恢复会话及凭据版本。确认前保留旧动态码，确认成功撤销旧会话、challenge 和 proof，其余未使用紧急码继续有效，不自动补码。

## 会话与安全操作

- `GET /me`、`POST /logout`：查询或撤销服务端会话。
- `POST /recovery-codes/get`：请求 `{ "code": "当前动态码" }`，完整管理员会话再次验证当前 TOTP 后获取紧急码。返回 `recoveryCodes`、`generatedCount`、`remaining`；首次生成 10 个，后续仅补缺口且保留原值。`local/password` 仅对已有绑定豁免 TOTP。没有绑定返回 409。
- `POST /recovery-codes/regenerate`：兼容别名，执行同样的补码规则。
- `POST /recovery/challenge`：所有环境可先验证账号密码以获得 LOGIN challenge；本地密码模式也可用该入口恢复已有绑定。
- `POST /operation/verify`：验证高危操作 challenge，签发 60 秒、一次性、绑定用户/会话/方法/目标的 proof。
- 所有管理员写请求必须携带受信任的精确 `Origin`。TOTP 模式下，管理员业务写请求先返回 HTTP 428
  `ADMIN_OPERATION_PROOF_REQUIRED`；验证后以 `X-Admin-Operation-Proof` 重试一次。`local/password` 模式跳过 step-up。

认证 challenge、会话、恢复码、proof 与审计均存数据库；Redis 限流或数据库不可用时认证 fail closed。
日志和审计不得记录密码、TOTP、TOTP 密钥、恢复码、cookie、challenge 或 proof 原文。

紧急码含 128 位安全随机数，以四组八位大写十六进制字符展示。输入兼容大小写、空格、连字符。
数据库保存 BCrypt 验证哈希与 AES-GCM 密文、nonce、密钥版本；加密上下文绑定 AISocialGame 后台、管理员和记录。
获取、消费、重绑均持有同一管理员事务锁，锁后采用 current read，保证至多 10 个有效码且同一码只能消费一次。
`backend/sql/20261004_admin_emergency_codes.sql` 已加入新建及升级清单；首次执行废弃历史恢复码、恢复会话与待确认重绑，保留当前有效种子和普通完整会话，重复执行不废弃新码。
后台 `/admin/security` 提供剩余数量、获取、复制、下载、关闭；所有认证响应 no-store，码仅保留页面内存，离开或退出即清除。
