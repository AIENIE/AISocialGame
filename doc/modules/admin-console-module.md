# Admin Console 模块

## 认证边界

- 管理台只接受本地配置管理员，后端赋予独立 `AUTH_LOCAL_ADMIN` authority；普通用户/SSO 会话不能进入管理台。
- 认证模式只读取 OS `ENV` / `AUTH_MODE`，合法组合为 `local/password`、`local/totp`、`test/totp`、
  `production/totp`。缺失、大小写变化、空白、引号或其他组合均拒绝启动。
- 管理员密码配置为 cost 至少 10 的 `APP_ADMIN_PASSWORD_HASH` BCrypt 摘要。TOTP 凭据绑定数据库，以版本化 AES-256-GCM
  keyring 加密；紧急码保存验证哈希及上下文绑定的 AES-GCM 密文，以支持重复展示未使用的码。
- 浏览器只使用 HttpOnly、SameSite=Strict 服务端会话 cookie；不在 localStorage/sessionStorage/JavaScript 内存保存管理员 token。
  `APP_ADMIN_COOKIE_SECURE=false` 仅允许 `ENV=local` 的纯 HTTP 调试；test/production 启动时强制 Secure。

## 登录与恢复

1. 密码验证成功后，TOTP 模式仅签发 120 秒、最多 5 次尝试的 challenge。
2. 已绑定账号输入 RFC 6238 SHA-1/6 位/30 秒 TOTP；首次登录进入绑定流程。
3. TOTP 在 ±1 时间步内校验，命中的 timestep 通过数据库条件更新全局原子推进，登录与操作 proof 之间也不可重放。
4. 恢复码必须在密码阶段之后使用，立即消费，仅建立最长 10 分钟受限恢复会话，并强制重绑后才能访问管理功能。
5. 登出、凭据重绑、认证策略变化或 credential version 变化都会使相关会话失效。

## 高危操作

所有 `/api/admin/*` 非只读业务请求（包括 DELETE、用户封禁、余额调整/冲正/迁移、兑换码、安全控制和
AI 记忆重置）统一执行 `428 -> TOTP -> 60 秒一次性 proof -> 原请求重试`。proof 绑定当前管理员、服务端会话、
HTTP 方法、目标路径、查询串和请求体摘要；绑定不匹配时拒绝，成功消费后不可复用。`local/password` 模式按策略豁免 step-up。

发版链路只负责构建与发布，不再登录管理员或自动调用 `migrate-all`。需要全量余额迁移时，由授权运维人员运行
`scripts/admin-billing-migrate-all.sh`：首次使用先完成 TOTP enrollment，随后交互完成密码、登录 TOTP 和操作 TOTP；
脚本退出时主动 logout，返回 `failed>0` 时以非零状态结束，服务端记录全程审计。部署前必须先幂等执行并核验
`backend/sql/20260810_admin_totp_auth.sql`。

## 紧急码操作与升级

安全设置入口为 `/admin/security`。首次绑定成功显示 10 个紧急码；现有绑定升级后首次获取生成 10 个。每次获取需完整会话和新的当前 TOTP（本地密码模式已有绑定可豁免），补充已消费的数量，其他码不变。重绑确认保留剩余码，不自动补充。
部署前通过迁移清单执行 `20261004_admin_emergency_codes.sql`，废弃全部历史 hash-only 恢复码和待确认恢复状态。密钥轮换时先保留旧 keyring 版本，获取会重加密现存紧急码，旧密钥不能在数据库记录迁移前删除。
Windows 隔离验收可使用 `Start-Local.ps1 -InstanceName admin-emergency-codes -IsolatedAcceptance -BackendPort 12031 -FrontendPort 12030 -EnvironmentFile <仓库外配置> -NoBrowser`。该开关仍校验系统矩阵共享 MySQL 主机和端口，仅允许 `aienie_emergency_20261004_social`；状态文件按实例隔离。状态/停止传入同样端口，停止还需同样 InstanceName，不影响默认实例。

隔离启动需要双重显式启用：仓库外 YAML 设置 `runtime.acceptance.admin-emergency-codes: true`、`server.address: 127.0.0.1`、`server.port: 12031`、`runtime.configuration.ENV: local`、`runtime.configuration.AUTH_MODE: totp`、`runtime.configuration.AIENIE_RUNTIME_PLANE: windows-local`；启动入口同时传 `-IsolatedAcceptance`。仅允许 Windows 原生 `local` profile、自有 `aienie_emergency_20261004_social` schema、系统矩阵当前共享 MySQL 的 `localmysql.testhut.top:23306`、可用 AES TOTP keyring。验收前端端口固定 12030，后台固定 12031。普通环境变量、命令行 selector 覆盖、其他环境/profile/schema/主机/端口都会拒绝；默认本地/生产 11031 与测试 20030 规则保持。结束后停止隔离实例，撤销整份验收 overlay 并恢复默认本地密码模式；不修改原始私有配置。
