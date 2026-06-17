# AISocialGame

基于 Spring Boot + React 的社交推理游戏平台。

## 技术栈

- 后端：Java 25、Spring Boot、MySQL、Redis、gRPC
- 前端：React 18、TypeScript、Vite、Tailwind、TanStack Query、shadcn/ui
- 部署：Docker Compose（仅编排本项目的前后端）

## 项目结构

- `frontend/`：前端源码、构建配置、Playwright 工具配置
- `backend/`：后端源码、SQL、proto、单测
- `doc/`：接口、模块、测试与运维文档
- `build.sh`：唯一部署脚本；默认使用 `aisocialgame.localhut.com`，可通过 `APP_DOMAIN` 覆盖
- `env.txt`：部署配置（可被系统环境变量覆盖）

## 认证与积分

- 登录/注册统一走 user-service SSO，本项目不提供本地账号体系。
- 项目专属积分在本项目本地账本管理。
- 通用积分由 pay-service 提供，并支持 1:1 兑换为项目永久专属积分。
- 首次登录会自动执行：
  - pay-service 用户初始化
  - 本地积分账户初始化

## gRPC 安全要求（严格）

默认启用 `APP_EXTERNAL_GRPC_AUTH_REQUIRED=true`，并要求以下变量非空：

- `APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN`
- `APP_EXTERNAL_PAYSERVICE_JWT`
- `APP_EXTERNAL_AISERVICE_HMAC_CALLER`
- `APP_EXTERNAL_AISERVICE_HMAC_SECRET`

缺失任一变量时，后端会在启动期 fail-fast。

## 运行依赖

项目依赖以下外部服务：

- MySQL
- Redis
- Qdrant
- user-service / pay-service / ai-service

本地 localhut 部署默认不再依赖 Consul：

- MySQL / Redis / Qdrant：`base.seekerhut.com:23306 / 26379 / 26333`
- user-service gRPC：`static://userservice.seekerhut.com:443`，TLS
- pay-service gRPC：`static://payservice.seekerhut.com:443`，TLS
- ai-service gRPC：`static://aiservice.seekerhut.com:443`，TLS
- SSO 入口：`https://userservice.seekerhut.com`

MySQL、Redis、Qdrant 由外部环境提供，项目脚本不负责部署、初始化或连通性预检。
如果本机 `env.local` 仍残留 `base.seekerhut.com:3306 / 6379 / 6333` 这组旧端口，`build.sh` 会在 Docker 部署前重写为 `23306 / 26379 / 26333`，避免容器因错误端口启动失败。

## 部署

### 启动前脚本清单

测试/正式环境默认以 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 启动，后端不会在启动时自动改表。涉及表结构变更的 SQL 需要先在目标数据库执行，确认成功后再启动应用。

一次性执行：

```bash
mysql \
  --host="${MYSQL_HOST:-base.seekerhut.com}" \
  --port="${MYSQL_PORT:-3306}" \
  --user="${SPRING_DATASOURCE_USERNAME:-aisocialgame}" \
  --password \
  aisocialgame < backend/sql/20260519_performance_stability.sql
```

说明：
- 执行前先备份目标库或至少备份 `rooms` 表。
- 同一环境只需要执行一次 `backend/sql/20260519_performance_stability.sql`；如果已经成功执行，不要重复执行，避免重复加列或重复建索引失败。
- 后续新增版本化 SQL 时，按文件日期顺序在测试/正式环境启动前执行。

每次部署执行：

```bash
./build.sh
```

如需切换域名，直接覆盖 `APP_DOMAIN`，不要再区分多套部署脚本：

```bash
APP_DOMAIN=aisocialgame.aienie.com ./build.sh
```

持续或可重复执行：
- `build.sh` 每次部署后会默认调用 `/api/admin/billing/migrate-all` 执行全量积分迁移；如需临时跳过，可设置 `RUN_FULL_MIGRATION=false`。
- 如果服务已经正常启动，但历史账本迁移返回 `Invalid token`，这属于业务数据迁移问题，不影响容器存活；可先用 `RUN_FULL_MIGRATION=false ./build.sh` 完成运行态部署，再单独处理迁移数据。

### Linux

统一部署：

```bash
./build.sh
APP_DOMAIN=aisocialgame.aienie.com ./build.sh
```

脚本流程包含：

1. 后端 `mvn clean test package`
2. 前端 `pnpm install --frozen-lockfile && pnpm build`
3. Docker Compose 重建前后端
4. 健康检查
5. 自动执行“全量积分迁移”

说明：
- 只维护一个 `build.sh`，环境差异通过 `APP_DOMAIN` 与已有环境变量覆盖，不再维护研发/生产双入口。
- 真实验收测试（含 4 场完整游戏）采用 subagent + Playwright 手工流程，不由 `build.sh` 自动触发。

### VS Code F5（以 `backend/` 为工作区根）

1. 在 VS Code 中直接打开 `backend/` 目录。
2. 选择调试配置 `Backend: Launch AiSocialGameApplication` 并按 `F5`。
3. 调试前会自动执行 `backend: compile`，用于生成 protobuf/gRPC 代码。
4. 调试进程会读取 `../env.txt`，未显式提供 `SERVER_PORT` 时会回退到 `BACKEND_PORT`。
5. 启动成功后，可访问 `http://127.0.0.1:11031/actuator/health` 验证服务状态。

## 域名与端口

- 默认域名：`aisocialgame.localhut.com`
- 可覆盖域名：`aisocialgame.aienie.com`
- 前端端口：`11030`
- 后端端口：`11031`

## 关键文档

- 结构：`doc/structure.md`
- 认证与钱包：`doc/modules/auth-wallet-module.md`
- gRPC 集成：`doc/modules/grpc-integration-module.md`
- 测试与运维：`doc/test/integratedTest.md`
