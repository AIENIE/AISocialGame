# 系统功能与操作步骤

## 0. 入口与端口

1. 本地域名入口：`https://localsocialgame.testhut.top`
2. 前端直连入口：`http://127.0.0.1:11030`
3. 后端直连入口：`http://127.0.0.1:11031/api`
4. 健康检查：`http://127.0.0.1:11031/actuator/health`
5. WebSocket：`wss://localsocialgame.testhut.top/ws`

## 1. 部署执行

1. 测试/正式环境通过发版中心发布：入口为 `scripts/ci/build-release.sh`（两阶段 Resolve/Build 契约，见 `scripts/ci/README.md`）；构建产物不包含运行时配置、秘密或证书。
2. Linux 服务器运行时配置（数据库、管理员 BCrypt 哈希、TOTP keyring、三服务 gRPC 鉴权变量）由 config-center 在部署侧注入，不使用仓库本地配置。
3. 确认 `GRPC_SHARED_SECRET` 未过期（推荐每次发布前重新生成）。
4. 启动前先幂等执行并核验 `backend/sql/20260810_admin_totp_auth.sql`；既有环境还应确认更早的日期迁移均已应用。
5. 发布完成后仅代表部署完成，测试需执行第 7 节真人验收流程；发版链路不登录管理员，也不执行任何特权迁移。

## 1.1 VS Code F5 调试（工作区根：`backend/`）

1. 在 VS Code 中打开 `backend/` 目录。
2. 运行调试配置 `Backend: Launch AiSocialGameApplication`。
3. 调试前会自动执行 `backend: compile`，确保 protobuf/gRPC 生成代码齐备。
4. 调试配置读取权限为 `0600` 且未入库的 `../env.local`（首次使用前先 `cp env.example env.local` 并填入真实值），因此会使用与本地运行一致的外部依赖配置。
5. 启动成功后访问 `http://127.0.0.1:11031/actuator/health`，确认返回健康状态。

## 2. SSO 登录/注册跳转

1. 首页点击“登录”。
2. 期望请求 `GET /api/auth/sso/login?state=<一次性状态>` 并返回 `302`。
3. 期望 `Location` 指向 user-service，且包含：
   - `redirect=https://localsocialgame.testhut.top/sso/callback`
   - `state=<原始状态值>`
4. 注册同理，通过 `GET /api/auth/sso/register?state=...`。
5. `state` 非法时返回 `400`。

## 3. SSO 回调安全校验

1. 打开 `https://localsocialgame.testhut.top/sso/callback?code=fake&state=bad` 并伪造 `state`。
2. 期望前端提示 `SSO 状态校验失败，请重新登录`。
3. 期望不会调用 `/api/auth/sso-callback` 换 token，回到首页且不会建立本地登录态。

## 4. 钱包与积分

1. 登录后进入 `/profile?tab=wallet`。
2. 校验余额区展示：
   - `publicPermanentTokens`
   - `projectTempTokens`
   - `projectPermanentTokens`
3. 执行 `100` 通用积分兑换专属积分：
   - 接口：`POST /api/wallet/exchange/public-to-project`
   - 期望：兑换成功，余额更新，记录写入；远程 pay-service 调用不占用本地数据库事务。
4. 查看 `通用积分兑换记录`：
   - 期望能看到 `兑换数量：100`
   - 期望展示 `通用积分：<前> -> <后>` 与 `项目永久积分：<前> -> <后>`。

## 5. 管理后台

1. 通过同一站点的 `/admin/login` 进入 React 内嵌管理台；管理员登录使用受控账号和密码，TOTP 模式完成第二阶段验证，浏览器只接收 HttpOnly cookie。
   - 首次登录必须先按页面或 `scripts/admin-billing-migrate-all.sh` 提示完成 enrollment，并将一次性恢复码保存到 owner-only 位置。
2. 进入积分管理页执行：
   - `migrate-user`
   - `migrate-all`
   - `adjust`
   - `reversal`
   - `redeem-codes`
3. 期望 `migrate-all` 返回 `scanned/success/failed` 与失败详情。
4. 批量迁移只由授权运维人员显式运行 `scripts/admin-billing-migrate-all.sh`；脚本会 logout，且 `failed>0` 返回非零状态。

## 6. AI 消耗记录

1. 用户执行 AI 对话（`/api/ai/chat` 或 `/api/ai/chat/stream`）。
2. 期望本地账本新增 `CONSUME` 流水。
3. 期望专属积分按 token 使用量扣减（临时优先）。

## 7. 游戏全流程验收（必须，subagent 真人）

1. 使用 subagent 执行真实用户操作，不使用自动化测试脚本。
2. 必测场景：
   - 谁是卧底：单人玩家 + AI
   - 谁是卧底：3 人玩家 + AI
   - 狼人杀：单人玩家 + AI
   - 狼人杀：3 人玩家 + AI
3. 期望所有场景都能到结算页，且无流程卡死。
4. 发言和投票必须结合场上信息，具备正常人类逻辑。
5. 测试账号由执行者通过 SSO 自行准备，不再从仓库文件读取。
6. 若余额不足，使用管理端创建兑换码并完成兑换后继续。
7. 产出 4 篇完整游戏报告到 `result/game-reports/<run-id>/`（本地产物，不入库）。

## 8. 常见故障与处置

1. 现象：`/api/auth/sso-callback` 返回 `401 Invalid token`。
2. 根因：`GRPC_SHARED_SECRET` 过期，导致后端调用 pay-service gRPC 认证失败。
3. 处理：
   - 用 pay-service 的 `JWT_SECRET` 重新签发服务 JWT（`iss=aienie-services`，`aud=aienie-payservice-grpc`，`role=SERVICE`，`scopes=[billing.balance.read,billing.balance.convert,billing.onboarding.write,billing.checkin.read,billing.checkin.write,billing.redeem.write,billing.ledger.read]`）。
   - 重新通过发版中心发布。

4. 现象：显式运行 `scripts/admin-billing-migrate-all.sh` 时，迁移接口报错
   `Missing scope: billing.balance.read` 或其他细粒度 scope。
5. 根因：签发 JWT 时误用 `scope` claim；pay-service 鉴权读取 `scopes`。
6. 处理：改为 `scopes` 数组并重新部署，再通过管理员登录和 TOTP 操作确认执行脚本，
   确认 `migrate-all` 返回 `failed=0`。

7. 现象：房间补满后刷新或重连，页面提示“等待当前玩家发言”，但当前真人看不到输入框。
8. 根因：后端 `RoomService.joinRoom` 在“已在房间重连”路径上先执行满房校验，导致 `myPlayerId/mySeatNumber` 绑定失败。
9. 处理：升级到包含该修复的版本（`joinRoom` 先判已在房再判满房），或临时避免在满房后刷新。

10. 现象：部署环境启动时报 schema validate 失败。
11. 根因：测试/正式环境默认不再使用 `ddl-auto:update` 自动改表。
12. 处理：先执行 `backend/sql/20260519_performance_stability.sql` 等迁移脚本，再重新启动。
