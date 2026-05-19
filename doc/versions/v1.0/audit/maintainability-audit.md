# AISocialGame v1.0 代码易读性与可维护性审计

- 审计日期：2026-05-19
- 审计范围：模块边界、代码复杂度、命名与类型、API 契约、测试覆盖、文档同步
- 风险等级：严重 / 高 / 中 / 低 / 建议

## 总体结论

项目已经完成 GameEngine 插件化的关键方向，后端也有较完整的服务/控制器分层和测试基础。但若继续扩展狼人杀、谁是卧底、海龟汤和 AI 质量闭环，目前几个大型服务类、重复页面和集中式 API 客户端会快速抬高维护成本。建议在继续做新玩法前，优先拆解游戏运行时、账务服务和前端房间 UI 的重复结构。

## 问题清单

| 等级 | 问题 | 证据 | 影响 |
| --- | --- | --- | --- |
| 高 | `GameRuntimeSupport` 过大，混合多游戏规则、状态推进、AI 自动行动、推送、回放 | `GameRuntimeSupport.java` 约 1388 行 | 新玩法或规则调整容易产生回归，单测定位困难 |
| 高 | `ProjectCreditService` 过大，混合账户、兑换码、消费、迁移、管理员调整、远程账务 | `ProjectCreditService.java` 约 906 行 | 账务逻辑修改牵一发而动全身，审计和幂等验证成本高 |
| 高 | 狼人杀/谁是卧底房间页重复 | `frontend/src/pages/games/UndercoverRoom.tsx` 约 372 行、`WerewolfRoom.tsx` 约 423 行 | UI 状态、WebSocket、错误处理、入座流程需双份维护 |
| 中 | 前端 API 客户端集中且类型边界较松 | `frontend/src/services/api.ts` 约 430 行、`Record<string, any>` 与 `error: any` | API 增长后难以定位调用方，错误结构缺少统一类型 |
| 中 | 控制器重复手写鉴权 | 多个 controller 直接读取 `X-Auth-Token` / `X-Admin-Token` | 权限策略分散，新增接口容易漏校验 |
| 中 | DTO 校验不均衡 | `AiChatRequest.java:40-45`、`CreateRoomRequest.java:18-25`、`AiOcrRequest.java` | 长度、枚举、URL、数量限制靠服务内部或缺失 |
| 中 | 历史文档存在 Consul 旧引用 | `doc/api/external/*-grpc.md` | 新成员容易误判当前服务发现方式 |
| 中 | 安全边界测试不足 | 现有测试覆盖服务逻辑较多，但未覆盖私密房、非房主加 AI、WebSocket 伪造身份 | 权限回归难以及时发现 |
| 低 | 部分模型/配置常量散落 | 游戏 ID、phase、role、状态字符串分布在 repository、runtime、前端 registry | 拼写错误和跨端不一致风险高 |

## 重点发现

### 1. 游戏运行时需要按玩法与能力拆分

项目已有 `GameEngine`、`GameEngineRegistry`、`UndercoverGameEngine`、`WerewolfGameEngine`，但实际对局执行仍大量集中在 `GameRuntimeSupport`。该类同时处理状态查询、开局、发言、投票、夜晚行动、断线接管、AI 自动行动、胜负判断、回放记录和推送。

建议：

- 保留 `GameRuntimeSupport` 作为编排门面，将玩法规则迁移到各 `GameEngine` 实现。
- 抽出通用能力：身份解析、phase timer、投票结算、AI 自动行动、回放事件记录、WebSocket 推送。
- 每个玩法用独立测试覆盖开局、发言、投票、超时、结算和私有信息可见性。

### 2. 账务服务需要按业务职责拆分

`ProjectCreditService` 同时承担本地账户初始化、签到、兑换码、消费、公共积分兑换、管理员调整、迁移、流水序列化和远程 pay-service 协调。账务属于高风险领域，当前单类维护会增加审计难度。

建议：

- 拆出 `CreditAccountService`、`RedeemCodeService`、`CreditConsumeService`、`CreditExchangeService`、`CreditAdminService`。
- 将流水写入封装成专门组件，统一 requestId 幂等、metadata 结构和反向冲正规则。
- 为远程账务交互建立明确状态机文档和测试。

### 3. 前端房间页面需要复用容器

`UndercoverRoom.tsx` 和 `WerewolfRoom.tsx` 都包含入座恢复、personas 查询、room/state 查询、WebSocket invalidation、阶段切换动画、start/speak/vote/addAi mutation、错误 toast、玩家列表和日志展示。重复会让新玩法接入成本升高。

建议：

- 抽出 `GameRoomShell` 或 `useRoomRuntime`，统一 room/state/socket/mutation/error 处理。
- 玩法页只提供 phase 面板、action 表单和规则特有展示。
- 将 `selfPlayerId` 从 `any` 访问改成明确响应类型。

### 4. API 契约与鉴权应集中化

前端 `api.ts` 集中所有业务 API，后端控制器逐个读取 token 并调用 `authService.authenticate` 或 `adminAuthService.requireAdmin`。这种方式短期直接，但新增接口时容易漏掉权限。

建议：

- 后端引入统一认证拦截器/参数解析器，例如 `@CurrentUser`、`@RequireAdmin`。
- 前端按领域拆分 `authApi`、`roomApi`、`gameplayApi`、`walletApi`、`adminApi` 文件。
- 统一错误响应类型，替换页面内重复 `error: any`。

### 5. 文档需要清理历史事实

`doc/api/external/user-service-grpc.md`、`pay-service-grpc.md`、`ai-service-grpc.md` 仍保留 Consul 字样。当前仓库配置以 static gRPC address 和域名路由为准，旧说明会造成部署与排障误导。

建议：

- 将 Consul 引用标为历史背景或删除。
- 在 `doc/modules/grpc-integration-module.md` 中明确当前发现方式：静态域名/端口 + 服务间鉴权。
- 审计文档完成后，在版本目录 README 或总览中链接三份审计报告。

## 测试与验收建议

- 后端：保留现有 `mvn test`，新增权限类测试覆盖私密房、房主校验、游客身份伪造、管理员默认配置。
- 前端：`pnpm lint` 应逐步收敛 `any` 和重复逻辑；房间页抽象后跑 `pnpm test:e2e`。
- 文档：每次玩法或外部服务接入变更后，同步更新 `doc/modules` 和 `doc/api`，避免设计文档与运行配置漂移。
- 代码指标：将单文件超过 500 行、服务类超过 8 个 public 方法、页面重复 mutation 流程作为重构触发信号。

