配置文件管理说明：[`application.yml` 与 `env.txt`](scripts/config-pair/README.md)。

# AISocialGame

基于 Spring Boot + React 的社交推理游戏平台。

## 技术栈

- 后端：Java 25、Spring Boot、MySQL、Redis、gRPC
- 前端：React 18、TypeScript、Vite、Tailwind、TanStack Query、shadcn/ui
- 管理台：同一 React/Vite 前端内的 `/admin` 路由入口，按路径独立分包；不是独立 Vue/`manage` 项目
- 部署：发版中心发布到 Linux Docker（`scripts/ci/build-release.sh` 唯一发版入口）

## 项目结构

- `frontend/`：前端源码、构建配置、Playwright 工具配置
- `backend/`：后端源码、SQL、proto、单测
- `doc/`：接口、模块、测试与运维文档
- `scripts/ci/`：发版中心两阶段发布契约（Resolve/Build，见 `scripts/ci/README.md`）
- `scripts/windows/`：Windows 本机调试启动脚本（Start-Backend.ps1 / Start-Frontend.ps1）
- `env.example`：无秘密的配置清单；复制为被忽略的 `env.local` 后填入真实值并设置权限 `0600`，仅供 VS Code F5 后端调试使用

## 认证与积分

- 登录/注册统一走 user-service SSO，本项目不提供本地账号体系。
- 项目专属积分在本项目本地账本管理。
- 通用积分由 pay-service 提供，并支持 1:1 兑换为项目永久专属积分。
- 首次登录会自动执行：
  - pay-service 用户初始化
  - 本地积分账户初始化

## gRPC 安全要求（严格）

默认启用 `APP_EXTERNAL_GRPC_AUTH_REQUIRED=true`，并要求以下变量非空：

- `APP_EXTERNAL_USERSERVICE_JWT_CALLER_ID=aisocialgame`
- `APP_EXTERNAL_USERSERVICE_JWT_ISSUER=aisocialgame`
- `GRPC_SHARED_SECRET`（当前环境共享、至少 32 UTF-8 字节）
- `APP_EXTERNAL_USERSERVICE_JWT_AUDIENCE=aienie-userservice-grpc`
- `APP_EXTERNAL_USERSERVICE_JWT_TTL_SECONDS=300`
- `APP_EXTERNAL_USERSERVICE_JWT_SCOPES=user.auth.session.read,user.directory.read,user.ban.read,user.ban.write`
- `GRPC_SHARED_SECRET`
- `APP_EXTERNAL_AISERVICE_HMAC_CALLER`
- `GRPC_SHARED_SECRET`

缺失任一变量时，后端会在启动期 fail-fast。
旧的 `APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN` 必须缺失或为空；任何非空值都会被 Java、Linux
部署预检和 Windows 启动器拒绝。每次 gRPC 调用在 `authorization: Bearer <JWT>` 中携带短期 caller JWT，旧 `x-internal-token` 禁用；进程只在
安全刷新窗口外复用缓存，不记录 token 或 secret。

## 运行依赖

项目依赖以下外部服务：

- MySQL
- Redis
- Qdrant
- user-service / pay-service / ai-service

本地部署默认不依赖 Consul：

- MySQL / Redis / Qdrant：`localmysql.testhut.top:23306 / 26379 / 26333`
- user-service gRPC：`static://localuserservice.testhut.top:22001`，TLS
- pay-service gRPC：`static://localpayservice.testhut.top:22021`，TLS
- ai-service gRPC：`static://localaiservice.testhut.top:22011`，TLS
- SSO 入口：`https://localuserservice.testhut.top`

MySQL、Redis、Qdrant 由外部环境提供，项目脚本不负责部署、初始化或连通性预检。

## 部署

### 上线前脚本清单（测试/正式环境）

测试/正式环境默认以 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 启动，后端不会在启动时自动改表。涉及表结构变更的 SQL 需要先在目标数据库执行，确认成功后再启动应用。

首次部署或版本升级时，先按日期顺序执行并核验迁移。管理员 TOTP 迁移使用
`CREATE TABLE IF NOT EXISTS`，可幂等重复核验：

```bash
mysql \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${SPRING_DATASOURCE_USERNAME}" \
  --password \
  aisocialgame < backend/sql/20260519_performance_stability.sql

mysql \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${SPRING_DATASOURCE_USERNAME}" \
  --password \
  aisocialgame < backend/sql/20260810_admin_totp_auth.sql
```

说明：
- 执行前先备份目标库；`20260519` 脚本仅在尚未应用时执行。
- 启动应用前必须确认 `backend/sql/20260810_admin_totp_auth.sql` 已成功应用，认证表均存在。
- 正式环境的 schema 计划由发版中心 production SQL ledger/checkpoint 合同管控（见 `scripts/ci/README.md`）。

### 发版中心发布（Linux Docker）

- 发版入口统一为 `scripts/ci/build-release.sh`（两阶段 Resolve/Build 契约，详见 `scripts/ci/README.md`）。
- 构建产物不包含任何运行时配置、秘密、证书或 Config Center 文件。
- Linux 服务器上的运行时配置（数据库、Redis、gRPC 鉴权等）由 config-center 在部署侧注入（运行时以 `env.txt` 形式挂载），不使用仓库本地配置。
- 发版链路只负责构建与发布，不登录管理员，也不自动执行余额迁移。
- 历史账本需要全量迁移时，由授权运维人员在发布完成后显式运行
  `scripts/admin-billing-migrate-all.sh`。脚本会在首次使用时先完成 TOTP enrollment，
  再交互完成管理员登录和操作确认，结束时主动退出；返回 `failed>0` 时脚本以非零状态结束。

### VS Code F5（以 `backend/` 为工作区根）

1. 在 VS Code 中直接打开 `backend/` 目录。
2. 选择调试配置 `Backend: Launch AiSocialGameApplication` 并按 `F5`。
3. 调试前会自动执行 `backend: compile`，用于生成 protobuf/gRPC 代码。
4. 调试进程会读取权限为 `0600` 且未入库的 `../env.local`，未显式提供 `SERVER_PORT` 时会回退到 `BACKEND_PORT`。首次使用前先 `cp env.example env.local && chmod 600 env.local` 并填入真实值。
5. 启动成功后，可访问 `http://127.0.0.1:11031/actuator/health` 验证服务状态。

### Windows 本机调试启动（共享 localbase）

共享 MySQL、Redis、Qdrant 和 AI/User/Pay 公共服务通过规范的 local* 域名访问；产品进程在 Windows 原生运行。准备好仓库外的 `%LOCALAPPDATA%\Aienie\secrets\aisocialgame.env` 后，一键启动：

```powershell
.\scripts\windows\Start-Local.ps1    # 后端+前端一次拉起为受管后台进程，健康检查通过后自动在默认浏览器打开本地域名主页
.\scripts\windows\Stop-Local.ps1     # 按进程身份记录停止
```

`Start-Local.ps1` 把下面两个前台调试脚本托管为隐藏后台进程并等待健康检查，重复运行安全（已运行的组件自动跳过；`-NoBrowser` 跳过打开浏览器，`-StartupTimeoutSeconds` 覆盖等待上限），并可从 Windows PowerShell 5.1 直接运行（自动转投 pwsh）。底层前台调试脚本仍然可用：

```powershell
.\scripts\windows\Start-Backend.ps1    # 后端：mvn spring-boot:run，127.0.0.1:11031
.\scripts\windows\Start-Frontend.ps1   # 前端：vite dev，127.0.0.1:11030
```

前台脚本日志直接输出到当前控制台，Ctrl+C 停止。首次运行若 `frontend/node_modules` 缺失会自动执行 `pnpm install --frozen-lockfile`；`Start-Backend.ps1` 可用 `-EnvironmentFile` 覆盖默认密钥文件路径。

调试入口不修改 hosts、ACL，不要求管理员权限或 UAC，也不访问 Config Center 或监控状态写入器。后端和前端分别限制在 `127.0.0.1:11031`、`127.0.0.1:11030`；跨服务只访问 `localbase.testhut.top` 和三个 `local*.testhut.top` TLS 服务。完整边界见 [`doc/operations/windows-native.md`](doc/operations/windows-native.md)。

## 域名与端口

- 本地域名：`localsocialgame.testhut.top`
- 预发布域名：`socialgame.testhut.top`
- 生产迁移目标：`socialgame.seekerhut.com`（完成切换验收前不得描述为当前入口）
- 历史生产兼容域名：`socialgame.aienie.com`（仅作迁移兼容；切换验收后至少保留 12 个月，不作为新配置或默认入口）
- 前端端口：`11030`
- 后端端口：`11031`

## 关键文档

- 结构：`doc/structure.md`
- 认证与钱包：`doc/modules/auth-wallet-module.md`
- gRPC 集成：`doc/modules/grpc-integration-module.md`
- 测试与运维：`doc/test/integratedTest.md`
- Windows 原生按需入口：`doc/operations/windows-native.md`

## 本地 L2 验证记录（2026-09-12）

Windows 根入口为 `start.ps1`，参数为 `-Action Start|Build|Test|Status|Stop`、`-Level L1|L2|L3`、`-Component All|Backend|Frontend`，默认 `Start/L2/All`。本地端口为前端 `11030`、后端 `11031`，主页为 `https://localsocialgame.testhut.top/`。

实际已记录的 L2 结果：后端 Maven 当前报告统计为 `119` 项执行、`0` failure、`0` error、`2` skipped，退出码 `0`（来自当前 `backend/target/surefire-reports/TEST-*.xml`）。前端 L2 为 2 个文件 / 19 项通过，退出码 0。完整启动的健康检查、HTTPS 与基础 API 已通过；本轮根入口双断点验收已完成。VS Code 根入口任务启动仓库根 `start.ps1`；建议在仓库根打开 VS Code，使用根目录 Java attach 与浏览器组合调试配置。

## 公共服务 gRPC 共享密钥（2026-10-02）

同一环境的三个公共服务及所有调用项目共用 env.txt 中的 `GRPC_SHARED_SECRET`，YAML 的客户端秘密属性统一引用 `${GRPC_SHARED_SECRET}`。develop 使用开发密钥，staging 和 production 各有独立值。此项不合并登录 JWT、管理员、TOTP、CAP、数据库或 Firefly 主站/Studio 内部密钥。新制品携带 `grpc-shared-key-v1` 能力标记；保存配置不表示服务已经发布，切换需协调服务端与调用方。
