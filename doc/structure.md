# 项目结构说明

> 更新时间：2026-05-19

## 结构树

AISocialGame/
├── backend/                                  # Spring Boot 后端
│   ├── sql/                                  # 后端 SQL
│   ├── src/main/java/com/aisocialgame/
│   │   ├── controller/                       # HTTP/WS 入口（含 auth、wallet、admin）
│   │   ├── engine/                           # GameEngine 插件化入口与玩法注册
│   │   ├── service/                          # 业务流程（SSO、本地积分、AI、后台运营）
│   │   ├── integration/                      # Consul + gRPC 调用封装
│   │   ├── model/                            # JPA 实体（含 credit 领域）
│   │   ├── repository/                       # 数据访问层
│   │   ├── dto/                              # 请求/响应模型
│   │   └── config/                           # 应用配置、CORS、WS 配置
│   ├── sql/ai_quality.sql                    # AI 决策 trace 与 Persona 记忆表
│   ├── sql/20260519_performance_stability.sql # v1.0 性能稳定性迁移
│   ├── sql/game_replays.sql                  # 结构化事件与服务端回放归档表
│   ├── src/main/resources/application.yml
│   └── src/test/
├── frontend/                                 # React + Vite 前端
│   ├── src/pages/
│   ├── src/components/
│   ├── src/services/
│   ├── tests/                                # Playwright 相关测试资产
│   └── playwright.config.ts
├── doc/                                      # 项目文档
├── design-doc/                               # 设计草案与历史方案文档
├── result/                                   # 本地测试产物（默认不入库）
├── docker-compose.yml                        # 仅编排本项目前后端容器
├── env.txt                                   # 无敏感值环境变量模板
├── env.local                                 # 本机真实环境变量（不入库）
├── build.sh                                  # 测试域名部署（Linux）
├── build_prod.sh                             # 正式域名部署（Linux）
├── build_common.sh                           # build 脚本共用逻辑
├── build_local.sh                            # Linux 宿主机后端直启入口
├── README.md
├── AGENTS.md
└── projectStructure.md

## 目录约束

- 前端代码必须位于 `frontend/`。
- 后端代码与 SQL 必须位于 `backend/`。
- `frontend/` 与 `backend/` 外仅保留：文档、部署脚本、测试结果、`env.txt` 与项目元信息。
- `result/` 为运行时产物目录（例如真人对局报告），由 `.gitignore` 忽略，不参与提交。
- `backend/.vscode/launch.json` 与 `backend/.vscode/tasks.json` 允许入库，用于 Java F5 调试；其他 `.vscode` 内容仍保持忽略。

## 部署脚本一致性

`build.sh` 与 `build_prod.sh` 当前保持同一实现，仅默认域名不同：

- `build.sh` -> `aisocialgame.seekerhut.com`
- `build_prod.sh` -> `aisocialgame.aienie.com`

两者共同调用 `build_common.sh`，并在运行期执行一致性校验。

## 关键配置约束

- gRPC 地址默认走静态域名：
  - `USER_GRPC_ADDR=static://userservice.seekerhut.com:10001`
  - `BILLING_GRPC_ADDR=static://payservice.seekerhut.com:20021`
  - `AI_GRPC_ADDR=static://aiservice.seekerhut.com:10011`
- SSO HTTP 入口通过 `SSO_USER_SERVICE_BASE_URL` 配置。
- 三服务 gRPC 鉴权变量通过未入库 `env.local`、系统环境或 CI/CD secret 注入，`env.txt` 只保留模板。
- 非 test profile 会校验弱口令、MySQL TLS 和 gRPC 明文配置，详见 `doc/modules/security-hardening-module.md`。
- 后端端口解析链路为 `SERVER_PORT -> BACKEND_PORT -> 20030`，宿主机直启默认复用 `env.txt` 中的 `BACKEND_PORT=11031`。
- `build_common.sh` 当前职责是构建、部署、依赖检查与迁移，不自动执行 Playwright。
- M1 AI 拟人质量闭环新增 `ai_decision_traces` 与 `ai_persona_memories`，用于服务端质检、回放准备和 Persona 记忆沉淀。
- 本地开箱即用数据由 `DemoSeedService` 管理，`build_local.sh` 默认开启，部署环境默认关闭；真实浏览器验收脚本位于 `frontend/tests/acceptance-real.spec.ts`。
- M2 结构化事件与回放新增 `game_events` 与 `game_archives`，`GameState.logs` 继续服务房间页，服务端回放 API 位于 `ReplayController`。
- M3 GameEngine 插件化新增 `backend/src/main/java/com/aisocialgame/engine/`，`GamePlayService` 作为兼容编排层，前端统一动作 hook 位于 `frontend/src/hooks/useGameEngine.ts`。
- v1.0 性能稳定性整改新增 `AsyncExecutionConfig`、`RequestIdFilter`、房间 `seatCount/version`、AI SSE 线程池和房间分页接口；详见 `doc/modules/performance-stability-module.md`。
