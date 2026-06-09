# 问题跟踪（更新于 2026-03-04）

> 历史记录：本文件保留问题演进过程，当前部署契约统一为 `build.sh`。

## 当前状态

当前无阻塞发布的已知问题。

## 已解决（本轮）

### 1. 三服务 gRPC 鉴权未统一注入

- 现象：调用 user/pay/ai 服务时，存在未携带必需 metadata 的风险。
- 修复：
  - 新增 `UserGrpcAuthClientInterceptor`
  - 新增 `BillingGrpcAuthClientInterceptor`
  - 新增 `AiGrpcHmacClientInterceptor`
  - 三个 gRPC client 全量挂载拦截器
- 验证：后端编译通过，调用路径可自动注入鉴权头。

### 2. 鉴权配置缺失导致运行时不确定失败

- 现象：缺失凭据时仅在运行时调用报错，不易排查。
- 修复：
  - 新增 `ExternalGrpcAuthValidator`
  - 默认 `APP_EXTERNAL_GRPC_AUTH_REQUIRED=true`
  - 缺失关键变量时启动期 fail-fast
- 验证：配置缺失会在启动阶段直接报错。

### 3. 部署入口曾经分散且迁移执行不稳定

- 现象：历史部署入口分散，迁移需人工触发。
- 修复：
  - 统一收敛到单 `build.sh`
  - 环境差异改为通过 `APP_DOMAIN` 与环境变量覆盖
  - 部署后自动执行 `migrate-all`
- 验证：脚本结构已统一并可自动迁移。

### 4. pay-service 服务 JWT 过期导致 SSO 回调 401

- 现象：`POST /api/auth/sso-callback` 返回 `401`，消息为 `Invalid token`。
- 根因：`APP_EXTERNAL_PAYSERVICE_JWT` 过期，`BillingGrpcClient.ensureUserInitialized` 调用被 pay-service gRPC 鉴权拒绝。
- 修复：
  - 重新签发满足 pay-service 鉴权约束的服务 JWT；
  - 通过 `sudo ./build.sh` 重新部署生效。
- 验证：SSO 回调可正常换取应用 token。

### 5. 部署入口漂移风险

- 现象：历史上部署入口曾分散，后续容易引入非环境变量层面的行为漂移。
- 修复：
  - 收敛为单 `build.sh`；
  - 环境差异统一通过 `APP_DOMAIN_DEFAULT` 等变量表达。
- 验证：当前仓库只保留一个部署入口。

### 6. 自动化 4 局脚本与真人验收策略冲突

- 现象：历史 `real-full-e2e.spec.ts` 通过脚本自动完成 4 局，和“必须 subagent 真人流程”要求冲突。
- 修复：
  - 移除 `frontend/tests/real-full-e2e.spec.ts`；
  - `build.sh` 不再自动执行 Playwright；
  - 统一改为部署后由 subagent 执行真人流程并产出报告。
- 验证：测试与运维文档已同步到真人验收口径。

### 7. AI 对话默认模型偶发不可用

- 现象：`/api/ai/chat` 在未显式指定模型时，返回 `模型不可用` 或 `AI 调用失败`。
- 根因：默认模型选择命中不可用文本模型，且未做候选模型回退。
- 修复：
  - `AiProxyService.chatByIdentity` 增加候选模型回退机制；
  - 对无显式模型请求，按候选模型顺序重试（默认模型 + 可用 TEXT 模型 id/name）；
  - 保持显式模型请求快速失败（便于问题暴露）。
- 验证：
  - 修复后 `POST /api/ai/chat` 默认模型路径返回 200；
  - `sudo ./build.sh` 完整重部署通过；
  - subagent Playwright 复测 AI 对话通过。

### 8. 满房重连时已在房用户被误判为“房间已满”

- 现象：三真人局中，房间补满后页面重连，出现“轮到当前真人发言但无输入框”，无法继续闭环。
- 根因：`RoomService.joinRoom` 先判断满房再判断“该用户是否已在房间”，导致重连时拿不到 `myPlayerId/mySeatNumber`。
- 修复：
  - 调整 `joinRoom` 判断顺序：先匹配已在房间的用户/玩家，再执行满房拦截；
  - 新增回归测试：`RoomServiceTest.joinRoomShouldAllowExistingAuthenticatedPlayerWhenRoomIsFull`。
- 验证：
  - 后端回归测试通过；
  - 修复后 “谁是卧底 3用户+AI” 可从开局推进至结算并产出完整报告。

### 9. 构建迁移阶段 JWT scope 字段不兼容

- 现象：`sudo ./build.sh` 执行到 `migrate-all` 报 `Missing scope: billing.balance.read` 或其他细粒度 scope，迁移失败。
- 根因：pay-service gRPC 鉴权读取 JWT `scopes` claim；错误使用了 `scope` 字段。
- 修复：
  - 重新签发 JWT，使用 `scopes: ["billing.balance.read","billing.balance.convert","billing.onboarding.write","billing.checkin.read","billing.checkin.write","billing.redeem.write","billing.ledger.read"]`。
- 验证：
  - `sudo ./build.sh` 成功完成，`migrate-all` 返回 `failed=0`。

## 观察项（非阻塞）

- 前端构建存在 chunk 体积告警（`index-*.js > 500kB`），不影响当前功能，可后续做按路由拆包优化。
