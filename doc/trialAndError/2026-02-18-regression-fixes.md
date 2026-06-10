# 2026-02-18 排查记录：DB/Redis 对齐与联调修复

## 1. MySQL/Redis 项目级配置对齐

- 目标：将数据库与 Redis 统一到 `192.168.1.4`，并使用项目专属数据库/Key 前缀。
- 处理：
  - `backend/src/main/resources/application.yml`
    - MySQL 默认连接改为外部托管实例地址，并移除项目侧建库参数。
    - Redis 默认连接改为 `192.168.1.4:6379`
    - 新增 `app.project-key=aisocialgame`
    - Token 前缀默认 `aisocialgame:auth:token:`
  - `backend/src/main/java/com/aisocialgame/config/TokenStoreConfig.java`
    - 增加前缀兜底规则：未配置时自动拼接 `${app.project-key}:auth:token:`
  - `backend/src/main/java/com/aisocialgame/service/token/RedisTokenStore.java`
    - 支持外部注入前缀并统一拼 key。
- 结果：后端可正常启动并通过健康检查，API 联调通过。

## 2. 本机缺少 mysql/redis-cli，且 Docker Hub 拉取失败

- 现象：
  - 本机无 `mysql` / `redis-cli`。
  - 使用 `docker run mysql:8.4` 和 `docker run redis:7.4-alpine` 拉镜像失败（网络限制）。
- 排查证据：
  - `artifacts/test/20260218-084707/mysql-check.log`
  - `artifacts/test/20260218-084707/redis-check.log`
- 解决策略：
  - 改为“应用链路验证”替代“客户端直连验证”：
    - 健康检查 `GET /actuator/health` 为 `UP`
    - `GET /api/games`、`/api/personas`、`/rooms/{id}`、`/state`、`/join` 均返回 200
    - 单测与前端构建全部通过
- 结论：在当前环境受限下，已通过可执行证据链证明 DB/Redis 可用。

## 3. 房间页 Join 403 与实时聊天中断

- 现象：
  - 本地缓存 token 失效时，房间页自动入座可能触发 `POST /join` 403。
  - 聊天区持续提示“连接中断，正在自动重连”。
- 根因：
  - 房间页自动入座时机与鉴权状态未完全对齐。
  - Vite 仅代理 `/api`，未代理 `/ws`，导致 WS 连接未到达后端。
- 修复：
  - `frontend/src/pages/games/UndercoverRoom.tsx`
  - `frontend/src/pages/games/WerewolfRoom.tsx`
    - 自动入座等待 `auth loading` 结束。
    - 已有 seat 状态下避免无效重复 join。
  - `frontend/src/services/api.ts`
    - 历史版本中 `roomApi.join` 在 `401/403` 时按未登录访客重试；v1.0 安全整改后已移除。
  - `frontend/vite.config.ts`
    - 新增 `/ws` 代理，`ws: true`，目标 `http://localhost:11031`。
- 验证：
  - 卧底/狼人房均可显示实时聊天消息，输入文本成功落地。
  - 证据：
    - `artifacts/test/20260218-084707/undercover-room-ws-ok.png`
    - `artifacts/test/20260218-084707/werewolf-room-ws-ok.png`
    - `artifacts/test/20260218-084707/playwright-console-ws-ok.log`

## 4. 补充：本地进程重启细节

- 现象：重启前端 dev server 时使用 `$pid` 变量报只读错误。
- 处理：改用 `$targetPid` 并使用 `npm.cmd` 启动。
- 结果：10030 端口恢复监听，前端服务正常。

## 5. 本轮遗留：社区发帖在前端页面触发 403（未闭环）

- 现象：
  - Playwright 在 `http://127.0.0.1:10030/community` 页面填写并点击“发布”后，帖子未创建。
  - 控制台出现 `POST /api/community/posts` 返回 `403 (Forbidden)`。
- 已尝试：
  - 清理本地 `aisocialgame_admin_token`。
  - 清理本地 `aisocialgame_token` 并刷新页面，按未登录访客再次发帖。
  - 结果仍为 `403`。
- 当前判断：
  - API 冒烟脚本中“社区发帖”可通过；仅前端交互链路出现 403，疑似页面态鉴权/请求头状态与接口预期不一致，需要进一步抓包对比 UI 请求与脚本请求差异。
- 证据：
  - `artifacts/test/20260218-091910/community-post-403.png`
  - `artifacts/test/20260218-091910/playwright-console-partial.log`
  - `artifacts/test/20260218-091910/playwright-network-partial.log`

后续状态（2026-06-06）：该条为历史遗留。后续 M4 安全治理已将社区发帖接入统一 safety gate，并补充中文访客名 URL 编码/后端解码；`CommunityServiceTest`、`frontend/tests/m4-safety.spec.ts` 和真实验收脚本中的社区发帖步骤覆盖该链路。

## 6. 本轮遗留：提前结束导致未执行的 UI 回归项

- 按用户要求提前结束后，以下场景尚未在 `20260218-091910` 轮次执行完成：
  - 卧底房页面文本聊天发送与落地断言。
  - 狼人房页面文本聊天发送与落地断言。
  - 管理台登录页账号密码输入与登录成功断言。
- 说明：
  - 相关 API 回归（含 admin 登录/鉴权接口）在本轮前已通过；
  - 但页面层证据仍需在下一轮补齐截图与日志。
