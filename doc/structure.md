# 项目结构说明

> 更新时间：2026-09-02

## 结构树

AISocialGame/
├── backend/                                  # Spring Boot 后端
│   ├── sql/                                  # 后端 SQL
│   ├── src/main/java/com/aisocialgame/
│   │   ├── controller/                       # HTTP/WS 入口（含 auth、wallet、admin）
│   │   ├── engine/                           # GameEngine 插件化入口与玩法注册
│   │   ├── service/                          # 业务流程（SSO、本地积分、AI、后台运营）
│   │   ├── integration/                      # 静态地址 gRPC 调用封装
│   │   ├── model/                            # JPA 实体（含 credit 领域）
│   │   ├── repository/                       # 数据访问层
│   │   ├── dto/                              # 请求/响应模型
│   │   ├── web/                              # MVC 参数解析器（当前用户/管理员）
│   │   └── config/                           # 应用配置、CORS、WS 配置
│   ├── sql/ai_quality.sql                    # AI 决策 trace 与 Persona 记忆表
│   ├── sql/20260519_performance_stability.sql # v1.0 性能稳定性迁移
│   ├── sql/game_replays.sql                  # 结构化事件与服务端回放归档表
│   ├── src/main/resources/application.yml
│   └── src/test/
├── frontend/                                 # React + Vite 前端
│   ├── src/main.tsx                          # /admin 精确分流到 App，其余路由加载 UserApp
│   ├── src/App.tsx                           # 内嵌 Admin 入口（独立 chunk、固定简中）
│   ├── src/UserApp.tsx                       # 用户端入口（i18n）
│   ├── src/pages/                            # 用户页与 pages/admin 管理页
│   ├── src/components/
│   ├── src/services/
│   ├── tests/                                # Playwright 相关测试资产
│   └── playwright.config.ts
├── doc/                                      # 项目文档
├── scripts/ci/                                       # 发版中心两阶段发布契约与运行时组装
├── result/                                   # 本地测试产物（默认不入库）
├── env.example                               # 无敏感值环境变量模板（本地 F5 调试用）
├── env.local                                 # 本机真实环境变量（不入库）
├── scripts/windows/                          # Windows 本机调试启动脚本（后端/前端各一，前台运行）
├── README.md
├── AGENTS.md
└── projectStructure.md

## 目录约束

- 前端代码必须位于 `frontend/`。
- 管理台是 `frontend/` 内按 `/admin` 路径加载的 React 入口，不存在第二套 Vue 或 `manage/` 前端；管理员权限由后端会话裁决。
- 后端代码与 SQL 必须位于 `backend/`。
- `frontend/` 与 `backend/` 外仅保留：文档、发版契约（`scripts/ci/`）、调试脚本、测试结果、`env.example` 与项目元信息。
- `result/` 为运行时产物目录（例如真人对局报告），由 `.gitignore` 忽略，不参与提交。
- `backend/.vscode/launch.json` 与 `backend/.vscode/tasks.json` 允许入库，用于 Java F5 调试；其他 `.vscode` 内容仍保持忽略。

## 部署脚本

- 发版入口：发版中心统一使用 `scripts/ci/build-release.sh`（两阶段 Resolve/Build 契约，Linux Docker 运行时配置由 config-center 提供）。
- Windows 本机调试入口：`scripts/windows/Start-Backend.ps1`、`Start-Frontend.ps1`（前台运行，Ctrl+C 停止，不写入进程状态文件）。
- 默认本地域名：`localsocialgame.testhut.top`

## 关键配置约束

- gRPC 地址默认走静态域名：
  - `USER_GRPC_ADDR=static://localuserservice.testhut.top:22001`
  - `BILLING_GRPC_ADDR=static://localpayservice.testhut.top:22021`
  - `AI_GRPC_ADDR=static://localaiservice.testhut.top:22011`
  - `USER_GRPC_NEGOTIATION_TYPE=TLS`、`BILLING_GRPC_NEGOTIATION_TYPE=TLS`、`AI_GRPC_NEGOTIATION_TYPE=TLS`
- SSO HTTP 入口通过 `SSO_USER_SERVICE_BASE_URL` 配置。
- 三服务 gRPC 鉴权变量在本地调试时通过权限为 `0600` 且未入库的 `env.local`（VS Code F5）或 `%LOCALAPPDATA%\Aienie\secrets\aisocialgame.env`（Windows 调试脚本）注入；发版环境由 config-center 在部署侧注入。`env.example` 只保留占位符清单。
- 非本地环境会校验弱口令、MySQL TLS 和 gRPC 明文配置；预生产 MySQL 固定为 `sslMode=REQUIRED&allowPublicKeyRetrieval=false`，生产要求 `VERIFY_IDENTITY&allowPublicKeyRetrieval=false`，详见 `doc/modules/security-hardening-module.md`。
- 发版链路（`scripts/ci/build-release.sh`）只负责依赖解析、构建、测试与运行时包组装，不登录管理员、不执行特权迁移，也不自动执行 Playwright；构建产物不含运行时配置与秘密。
- M1 AI 拟人质量闭环新增 `ai_decision_traces` 与 `ai_persona_memories`，用于服务端质检、回放准备和 Persona 记忆沉淀。
- 本地开箱即用数据由 `DemoSeedService` 管理，只有显式设置 `APP_DEMO_SEED_ENABLED=true` 时才会开启；真实浏览器验收脚本位于 `frontend/tests/acceptance-real.spec.ts`。
- M2 结构化事件与回放新增 `game_events` 与 `game_archives`，`GameState.logs` 继续服务房间页，服务端回放 API 位于 `ReplayController`。
- M3 GameEngine 插件化新增 `backend/src/main/java/com/aisocialgame/engine/`，`GamePlayService` 作为兼容编排层，前端统一动作 hook 位于 `frontend/src/hooks/useGameEngine.ts`。
- v1.0 性能稳定性整改新增 `AsyncExecutionConfig`、`RequestIdFilter`、房间 `seatCount/version`、AI SSE 线程池和房间分页接口；详见 `doc/modules/performance-stability-module.md`。
- v1.0 可维护性整改新增 `backend/src/main/java/com/aisocialgame/web/`、`backend/src/main/java/com/aisocialgame/service/credit/CreditLedgerService.java` 和 `frontend/src/pages/games/shared/`，用于集中鉴权参数解析、账本逻辑与房间页复用。
