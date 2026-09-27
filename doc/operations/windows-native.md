# Windows 本机调试启动

2026-09-22：受管后端启动、验收账号准备、迁移与指标入口改为共用 `SharedMySqlTarget.ps1`。当前矩阵 13306 与旧环境文件 23306 冲突，入口会在连接前返回 UNKNOWN；需先取得实际映射及配置的一致证据，不能轮流试连。只读准备使用 `Test-ClosurePreflight.ps1`，结果及剩余条件见[只读预检报告](../test/readonly-preflight-20260922.md)。


日常入口为仓库根 `start.ps1`（PowerShell 7）；支持 Start / Build / Test / Status / Stop。默认启动执行必要构建，测试使用 `-Action Test -Level L2`。VS Code 请打开仓库根目录。实际验收与已知阻塞见 [本地开发验收](local-development-verification.md)。下文保留底层脚本与环境配置说明。

在仓库检出目录使用 PowerShell 7。一键入口 `Start-Local.ps1` 把两个前台调试脚本
托管为受管隐藏后台进程并等待健康检查，重复运行安全（已运行组件自动跳过、陈旧
记录自动清理），启动成功后自动在默认浏览器打开本地域名主页（`-NoBrowser` 跳过）；
它也兼容从 Windows PowerShell 5.1 直接运行（自动转投 pwsh）。仍保留两个前台调试
脚本，直接在 Windows 上运行 AISocialGame，不经过 Config Center、发布平面或监控
状态写入器。

| 操作 | 命令 |
| --- | --- |
| 启动（一键，含打开主页） | `.\scripts\windows\Start-Local.ps1` |
| 停止受管后台实例 | `.\scripts\windows\Stop-Local.ps1` |
| 前台启动后端（127.0.0.1:11031） | `.\scripts\windows\Start-Backend.ps1` |
| 前台启动前端（127.0.0.1:11030） | `.\scripts\windows\Start-Frontend.ps1` |

前台脚本日志直接输出到当前控制台，Ctrl+C 停止，退出码透传给调用方；
`Start-Local.ps1` 托管的实例日志位于
`%LOCALAPPDATA%\Aienie\native-runs\aisocialgame\logs`，进程记录在同目录
`processes.json`，由 `Stop-Local.ps1` 按进程身份停止。

默认私有输入为 `%LOCALAPPDATA%\Aienie\secrets\aisocialgame.env`（必须是普通
非 reparse 文件，可用 `-EnvironmentFile` 覆盖）。不要求特殊 ACL、管理员所有权、
UAC 或提权。文件保存凭据与 caller-auth 值；本地端口、身份、数据端点、SSO 端点
与公共服务目标由脚本固定。

启动拒绝继承或文件提供的非本地 `ENV`、`APP_ENV`、`SPRING_PROFILES_ACTIVE`
值，随后强制本地 profile、`AIENIE_RUNTIME_PLANE=windows-local`、
`APP_PROJECT_KEY=aisocialgame` 与回环端口 11031/11030；同时剥离
`JAVA_TOOL_OPTIONS`、`MAVEN_OPTS`、`SPRING_APPLICATION_JSON`、`SPRING_CONFIG_*`
等进程注入变量，并在启动前执行 UserService caller JWT 契约校验（拒绝旧
`APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN` 非空值）。后端由
`mvn spring-boot:run` 启动，前端由 `pnpm exec vite` 启动；前端依赖缺失时自动
执行 `pnpm install --frozen-lockfile`。健康验证为
`http://127.0.0.1:11031/actuator/health` 返回 HTTP 200 且顶层 `status=UP`。

Windows 产品实例与 `aienie-wsl` 依赖平面分离。MySQL、Redis、Qdrant 使用
`localbase.testhut.top`；公共服务 AI/User/Pay 调用三个 `local*.testhut.top`
TLS 端点。Java 使用当前 JDK/JVM 信任库。脚本不会把公共服务重定向到 Windows
回环端口，也不启用 trust-all 或明文回退。

### 项目 Node 与隔离数据库验证

先运行 `scripts/windows/Prepare-ProjectNode.ps1` 准备 package.json 指定的 Node/pnpm。工具安装于 `%LOCALAPPDATA%\Aienie\tools\aisocialgame`，官方 Node 包校验 SHA-256；标准构建、测试和前端启动仅对当前进程树切换工具链，版本缺失时失败而不静默下载，不改变系统 Node。构建包含 `tsc --noEmit`，L2 还包含工具链环境恢复回归。

`scripts/windows/Test-ClosureMySql.ps1 -EvidenceDirectory <仓库外新目录>` 显式运行 MySQL 8.0.45 原生隔离测试，官方 ZIP 校验后以独立数据目录和回环端口启动。无系统服务、无共享数据源、无模型请求；结束时停止所属进程并清理测试数据/凭据，保存迁移、并发及事务证据。日常 L2 不启动该实例。结果和限制见 [环境门禁报告](../test/environment-gates-20260922.md)。
