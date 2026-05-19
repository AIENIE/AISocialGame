# 系统功能与操作步骤（2026-03-04）

## 0. 入口与端口

1. 测试域名入口：`https://aisocialgame.seekerhut.com`
2. 前端直连入口：`http://127.0.0.1:11030`
3. 后端直连入口：`http://127.0.0.1:11031/api`
4. 健康检查：`http://127.0.0.1:11031/actuator/health`
5. WebSocket：`wss://aisocialgame.seekerhut.com/ws`

## 1. 部署执行

1. 注入三服务 gRPC 鉴权环境变量（四项必须）。
2. 确认 `APP_EXTERNAL_PAYSERVICE_JWT` 未过期（推荐每次部署前重新生成）。
3. 执行 `sudo ./build.sh`。
4. 期望输出包含：
   - 后端测试通过
   - 前端构建完成
   - 容器重建完成
   - `Run full credit migration` 且 `failed=0`
5. `build.sh` 结束后仅代表部署完成，测试需执行第 7 节真人验收流程。

## 1.1 Linux 宿主机直启后端

1. 在仓库根目录执行 `./build_local.sh`。
2. 脚本会先读取根目录 `env.txt`，仅为当前 shell 未设置的变量补默认值。
3. 当 `SERVER_PORT` 未设置时，后端会按 `BACKEND_PORT -> 20030` 回退；默认健康检查地址仍为 `http://127.0.0.1:11031/actuator/health`。
4. 若 `APP_EXTERNAL_GRPC_AUTH_REQUIRED=true` 且以下变量任一缺失，脚本会直接失败：
   - `APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN`
   - `APP_EXTERNAL_PAYSERVICE_JWT`
   - `APP_EXTERNAL_AISERVICE_HMAC_CALLER`
   - `APP_EXTERNAL_AISERVICE_HMAC_SECRET`

## 1.2 VS Code F5 调试（工作区根：`backend/`）

1. 在 VS Code 中打开 `backend/` 目录。
2. 运行调试配置 `Backend: Launch AiSocialGameApplication`。
3. 调试前会自动执行 `backend: compile`，确保 protobuf/gRPC 生成代码齐备。
4. 调试配置读取 `../env.txt`，因此默认会连接共享 MySQL、Redis、Qdrant 与三服务静态地址。
5. 启动成功后访问 `http://127.0.0.1:11031/actuator/health`，确认返回健康状态。

## 2. SSO 登录/注册跳转

1. 首页点击“登录”。
2. 期望请求 `GET /api/auth/sso/login?state=<一次性状态>` 并返回 `302`。
3. 期望 `Location` 指向 user-service，且包含：
   - `redirect=https://aisocialgame.seekerhut.com/sso/callback`
   - `state=<原始状态值>`
4. 注册同理，通过 `GET /api/auth/sso/register?state=...`。
5. `state` 非法时返回 `400`。

## 3. SSO 回调安全校验

1. 打开 `https://aisocialgame.seekerhut.com/sso/callback` 并伪造 `state`。
2. 期望前端提示 `SSO 状态校验失败，请重新登录`。
3. 期望回到首页且不会建立本地登录态。

## 4. 钱包与积分

1. 登录后进入 `/profile?tab=wallet`。
2. 校验余额区展示：
   - `publicPermanentTokens`
   - `projectTempTokens`
   - `projectPermanentTokens`
3. 执行 `100` 通用积分兑换专属积分：
   - 接口：`POST /api/wallet/exchange/public-to-project`
   - 期望：兑换成功，余额更新，记录写入。
4. 查看 `通用积分兑换记录`：
   - 期望能看到 `兑换数量：100`
   - 期望展示 `通用积分：<前> -> <后>` 与 `项目永久积分：<前> -> <后>`。

## 5. 管理后台

1. 管理员登录：使用 `APP_ADMIN_USERNAME`/`APP_ADMIN_PASSWORD` 中配置的受控账号。
2. 进入积分管理页执行：
   - `migrate-user`
   - `migrate-all`
   - `adjust`
   - `reversal`
   - `redeem-codes`
3. 期望 `migrate-all` 返回 `scanned/success/failed` 与失败详情。

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
5. 账号从根目录 `testuser.txt` 读取。
6. 若余额不足，使用管理端创建兑换码并完成兑换后继续。
7. 产出 4 篇完整游戏报告到 `result/game-reports/<run-id>/`（本地产物，不入库）。

## 8. 常见故障与处置

1. 现象：`/api/auth/sso-callback` 返回 `401 Invalid token`。
2. 根因：`APP_EXTERNAL_PAYSERVICE_JWT` 过期，导致后端调用 pay-service gRPC 认证失败。
3. 处理：
   - 用 pay-service 的 `JWT_SECRET` 重新签发服务 JWT（`iss=aienie-services`，`aud=aienie-payservice-grpc`，`role=SERVICE`，`scopes=[billing.read,billing.write]`）。
   - 重新执行 `sudo ./build.sh` 部署。

4. 现象：`sudo ./build.sh` 的 `migrate-all` 报错 `Missing scope: billing.read`。
5. 根因：签发 JWT 时误用 `scope` claim；pay-service 鉴权读取 `scopes`。
6. 处理：改为 `scopes` 数组并重新部署，确认 `migrate-all` 返回 `failed=0`。

7. 现象：房间补满后刷新或重连，页面提示“等待当前玩家发言”，但当前真人看不到输入框。
8. 根因：后端 `RoomService.joinRoom` 在“已在房间重连”路径上先执行满房校验，导致 `myPlayerId/mySeatNumber` 绑定失败。
9. 处理：升级到包含该修复的版本（`joinRoom` 先判已在房再判满房），或临时避免在满房后刷新。
