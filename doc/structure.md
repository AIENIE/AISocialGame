# 项目结构说明

> 更新时间：2026-03-04

## 结构树

AISocialGame/
├── backend/                                  # Spring Boot 后端
│   ├── sql/                                  # 后端 SQL
│   ├── src/main/java/com/aisocialgame/
│   │   ├── controller/                       # HTTP/WS 入口（含 auth、wallet、admin）
│   │   ├── service/                          # 业务流程（SSO、本地积分、AI、后台运营）
│   │   ├── integration/                      # Consul + gRPC 调用封装
│   │   ├── model/                            # JPA 实体（含 credit 领域）
│   │   ├── repository/                       # 数据访问层
│   │   ├── dto/                              # 请求/响应模型
│   │   └── config/                           # 应用配置、CORS、WS 配置
│   ├── sql/ai_quality.sql                    # AI 决策 trace 与 Persona 记忆表
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
├── env.txt                                   # 部署环境变量
├── build.sh                                  # 测试域名部署（Linux）
├── build_prod.sh                             # 正式域名部署（Linux）
├── build_common.sh                           # build 脚本共用逻辑
├── build_local.sh                            # Linux 宿主机后端直启入口
├── build_local.ps1                           # 本地 PowerShell 部署
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

- gRPC 地址默认走 consul：
  - `USER_GRPC_ADDR=consul:///aienie-userservice-grpc`
  - `BILLING_GRPC_ADDR=consul:///aienie-payservice-grpc`
  - `AI_GRPC_ADDR=consul:///aienie-aiservice-grpc`
- Consul 地址通过 `CONSUL_HTTP_ADDR` 配置，不在业务代码写死。
- 三服务 gRPC 鉴权变量通过 `env.txt` + 系统环境注入。
- 后端端口解析链路为 `SERVER_PORT -> BACKEND_PORT -> 20030`，宿主机直启默认复用 `env.txt` 中的 `BACKEND_PORT=11031`。
- `build_common.sh` 当前职责是构建、部署、依赖检查与迁移，不自动执行 Playwright。
- M1 AI 拟人质量闭环新增 `ai_decision_traces` 与 `ai_persona_memories`，用于服务端质检、回放准备和 Persona 记忆沉淀。
- 本地开箱即用数据由 `DemoSeedService` 管理，`build_local.sh` 默认开启，部署环境默认关闭；真实浏览器验收脚本位于 `frontend/tests/acceptance-real.spec.ts`。
