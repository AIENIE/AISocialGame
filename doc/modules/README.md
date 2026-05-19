# 模块文档索引

> 更新时间：2026-05-19

| 模块 | 作用 | 入口实现位置 |
|---|---|---|
| 大厅与房间模块 | 管理创建房间、入座、AI 补位、座位广播与房间页入口 | `backend/src/main/java/com/aisocialgame/controller/RoomController.java`、`backend/src/main/java/com/aisocialgame/service/RoomService.java`、`frontend/src/pages/Lobby.tsx`、`frontend/src/pages/games/shared/*` |
| 游戏流程模块 | 管理对局状态、阶段推进、超时处理、断线托管与结算 | `backend/src/main/java/com/aisocialgame/controller/GamePlayController.java`、`backend/src/main/java/com/aisocialgame/service/GamePlayService.java` |
| GameEngine 插件化模块 | 管理玩法 engine 注册、统一 action 和现有玩法插件化入口 | `backend/src/main/java/com/aisocialgame/engine/*.java`、`frontend/src/hooks/useGameEngine.ts`、`doc/modules/game-engine-module.md` |
| AI 决策模块 | 管理 AI 玩家上下文构造、Prompt 调用、JSON 决策解析与规则兜底 | `backend/src/main/java/com/aisocialgame/service/ai/*.java`、`backend/src/main/resources/prompt.yml`、`doc/modules/ai-decision-module.md` |
| AI 拟人质量闭环模块 | 管理 AI 信念、局内短期记忆、Persona 跨局记忆、质量检测、决策 trace 与管理端质检 | `backend/src/main/java/com/aisocialgame/model/AiDecisionTrace.java`、`backend/src/main/java/com/aisocialgame/model/AiPersonaMemory.java`、`doc/modules/ai-quality-loop-module.md` |
| 结构化事件与服务端回放模块 | 管理服务端对局事件流、单局归档、回放查询和视角过滤 | `backend/src/main/java/com/aisocialgame/model/GameEvent.java`、`backend/src/main/java/com/aisocialgame/model/GameArchive.java`、`backend/src/main/java/com/aisocialgame/controller/ReplayController.java`、`doc/modules/replay-event-module.md` |
| 本地开箱即用数据与验收模块 | 管理本地 demo seed、可复用 Playwright 验收和管理端 AI 质检入口 | `backend/src/main/java/com/aisocialgame/service/DemoSeedService.java`、`frontend/tests/acceptance-real.spec.ts`、`doc/modules/demo-seed-and-acceptance-module.md` |
| 房间实时通信模块 | 管理 STOMP 鉴权、状态推送、座位推送、房间聊天与连接状态 | `backend/src/main/java/com/aisocialgame/config/WebSocketConfig.java`、`backend/src/main/java/com/aisocialgame/controller/RoomChatController.java`、`backend/src/main/java/com/aisocialgame/websocket/*.java`、`frontend/src/hooks/useGameSocket.ts` |
| 性能与稳定性加固模块 | 管理账务事务边界、AI SSE 线程池、房间行锁、分页查询、运行时状态清理和 request id | `doc/modules/performance-stability-module.md`、`backend/src/main/java/com/aisocialgame/config/AsyncExecutionConfig.java`、`backend/src/main/java/com/aisocialgame/config/RequestIdFilter.java` |
| v2 社交留存与导航模块 | 覆盖快速匹配、好友、成就、回放、观战、新手引导与全局导航入口 | `doc/modules/v2-social-retention-module.md`、`frontend/src/components/social/*`、`frontend/src/pages/{Achievements,Replays,ReplayPlayer,Guide,SpectatorRoom}.tsx`、`frontend/src/services/v2Social.ts` |
| 认证与钱包模块 | 管理 SSO 登录态、本地专属积分、签到、兑换码、通用转专属兑换、AI 成功调用后本地扣减 | `backend/src/main/java/com/aisocialgame/controller/AuthController.java`、`backend/src/main/java/com/aisocialgame/controller/WalletController.java`、`backend/src/main/java/com/aisocialgame/service/ProjectCreditService.java`、`frontend/src/components/wallet/*` |
| 管理后台模块 | 管理管理员登录、用户封禁、积分流水检查、调账/冲正/迁移、兑换码创建、联通性诊断 | `backend/src/main/java/com/aisocialgame/controller/admin/*.java`、`frontend/src/pages/admin/*` |
| gRPC 集成模块 | 管理 user/pay/ai 静态地址调用封装与严格鉴权拦截器 | `backend/src/main/java/com/aisocialgame/integration/*` |
| 第三方组件对齐模块 | 记录 MySQL/Redis/Qdrant 外部依赖对接与配置策略 | `doc/modules/third-party-components.md`、`backend/src/main/resources/application.yml`、`env.txt`、`build.sh` |
| 安全加固模块 | 记录 v1.0 审计后的登录边界、房间权限、WS/CORS、AI 接口与运行配置加固 | `doc/modules/security-hardening-module.md`、`backend/src/main/java/com/aisocialgame/config/*` |
| 可维护性整改模块 | 记录 v1.0 审计后的鉴权参数解析、房间页复用、账务账本拆分和 DTO 校验边界 | `doc/versions/v1.0/audit/maintainability-remediation.md`、`backend/src/main/java/com/aisocialgame/web/*` |

## 后续里程碑

- 后续开发主线见 `doc/milestones.md`，已完成里程碑的阶段修改记录见 `doc/milestones/README.md`。
- 当前默认优先级为 AI 质量闭环、结构化事件与回放底座、GameEngine 插件化、海龟汤新增玩法。

## 回归说明

- `build.sh` 当前仅负责构建、部署、健康检查与积分迁移，不自动执行 Playwright。
- 真实验收统一采用 subagent + Playwright 真人流程，详见 `doc/test/integratedTest.md` 与 `doc/test/operation.md`。
