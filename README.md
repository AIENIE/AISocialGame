# AISocialGame

基于 Spring Boot + React 的社交推理游戏平台。

## 技术栈

- 后端：Java 21、Spring Boot、MySQL、Redis、gRPC
- 前端：React 18、TypeScript、Vite、Tailwind、TanStack Query、shadcn/ui
- 部署：Docker Compose（仅编排本项目的前后端）

## 项目结构

- `frontend/`：前端源码、构建配置、Playwright 工具配置
- `backend/`：后端源码、SQL、proto、单测
- `doc/`：接口、模块、测试与运维文档
- `build.sh`：测试域名部署脚本（`aisocialgame.seekerhut.com`）
- `build_prod.sh`：正式域名部署脚本（`aisocialgame.aienie.com`）
- `build_common.sh`：`build.sh/build_prod.sh` 共用部署逻辑
- `build_local.sh`：Linux 宿主机本地直启后端
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

测试服默认不再依赖 Consul：

- MySQL / Redis / Qdrant：`base.seekerhut.com` 标准端口 `3306 / 6379 / 6333`
- user-service gRPC：`static://userservice.seekerhut.com:10001`
- pay-service gRPC：`static://payservice.seekerhut.com:20021`
- ai-service gRPC：`static://aiservice.seekerhut.com:10011`
- SSO 入口：`https://userservice.seekerhut.com`

MySQL、Redis、Qdrant 由外部环境提供，项目脚本不负责部署、初始化或连通性预检。

## 部署

### Linux

测试环境部署：

```bash
./build.sh
```

正式环境部署：

```bash
./build_prod.sh
```

脚本流程包含：

1. 后端 `mvn clean test package`
2. 前端 `pnpm install --frozen-lockfile && pnpm build`
3. Docker Compose 重建前后端
4. 健康检查
5. 自动执行“全量积分迁移”

说明：
- `build.sh` / `build_prod.sh` 仅默认域名不同，其他逻辑必须保持一致。
- 真实验收测试（含 4 场完整游戏）采用 subagent + Playwright 手工流程，不由 `build.sh` 自动触发。

### Linux（宿主机直启后端）

```bash
./build_local.sh
```

说明：
- 脚本在仓库根目录读取 `env.txt`，仅为当前 shell 中未设置的变量补默认值。
- 脚本会导出 `SERVER_PORT="${SERVER_PORT:-${BACKEND_PORT:-11031}}"`，因此可直接复用 `env.txt` 中的 `BACKEND_PORT=11031`。
- 当 `APP_EXTERNAL_GRPC_AUTH_REQUIRED=true` 时，启动前会校验 4 个外部 gRPC 鉴权变量，缺失即快速失败。
- 启动成功后，健康检查地址为 `http://127.0.0.1:11031/actuator/health`。

### VS Code F5（以 `backend/` 为工作区根）

1. 在 VS Code 中直接打开 `backend/` 目录。
2. 选择调试配置 `Backend: Launch AiSocialGameApplication` 并按 `F5`。
3. 调试前会自动执行 `backend: compile`，用于生成 protobuf/gRPC 代码。
4. 调试进程会读取 `../env.txt`，未显式提供 `SERVER_PORT` 时会回退到 `BACKEND_PORT`。
5. 启动成功后，可访问 `http://127.0.0.1:11031/actuator/health` 验证服务状态。

## 域名与端口

- 测试域名：`aisocialgame.seekerhut.com`
- 正式域名：`aisocialgame.aienie.com`
- 前端端口：`11030`
- 后端端口：`11031`

## 关键文档

- 结构：`doc/structure.md`
- 认证与钱包：`doc/modules/auth-wallet-module.md`
- gRPC 集成：`doc/modules/grpc-integration-module.md`
- 测试与运维：`doc/test/integratedTest.md`
