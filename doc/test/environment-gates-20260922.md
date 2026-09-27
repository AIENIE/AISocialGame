# M1～M5 环境门禁：Node 与隔离 MySQL

日期：2026-09-22。Node 目标版本及真实 MySQL 隔离验证已完成；共享环境应用、真实模型质量及最终六局仍未执行，**L4 未通过**。

## 实施

- Node 22.23.2、pnpm 11.22.0 从 frontend/package.json 解析。官方 Node ZIP 按发布 SHA-256 校验，安装在仓库外项目工具目录。构建、测试、前端启动及六局验收使用同一解析入口，精确版本检查失败即停止。
- 工具仅影响本次脚本及子进程；PATH、Corepack 缓存与网络设置在 finally 恢复，不替换系统 Node。普通入口禁止 Corepack 静默下载工具，准备由显式脚本执行。
- 标准前端构建包含 tsc --noEmit。移除已被 pnpm 11 忽略的 package.json.pnpm 重复配置，保留现有 pnpm-workspace.yaml 的 allowBuilds 白名单。
- MySQL 8.0.45 官方 ZIP 核对官方 MD5，并另存 SHA-256。临时原生进程使用独立目录、随机凭据和回环端口，不注册服务，不使用 Docker/WSL，不接触共享数据库。
- JDBC 使用完整原始 schema 和三个增量 SQL，未转换 PREPARE/EXECUTE；旧库夹具保留来源提交。新库、旧库升级、已有 v2 升级均执行增量重跑两次；部分 DDL 已完成后的失败可继续迁移。
- 比较 329 个列定义及 118 个索引列定义，三条路径一致。合法房主 JSON 只产生预期回填，无效 JSON 不被修改；旧状态、记忆、归档、trace、累计预算及 NULL 用量保持。无需修改生产 SQL。
- 真实 MySQL 上复用生产调用准入与持久化实现，验证四种作用域并发、完整上下文重建、未知用量、重复完成、回滚后审计，以及实际 AiGrpcClient 拒绝路径没有 RPC。复用现有 18 项游戏事务测试验证回滚、幂等、过期、控制和权限行为。

## 可重复入口

在仓库根目录使用 PowerShell 7：

```powershell
.\scripts\windows\Prepare-ProjectNode.ps1
.\scripts\windows\Test-Local.ps1 -Component All -Level L2
.\scripts\windows\Test-ClosureMySql.ps1 -EvidenceDirectory <仓库外全新绝对路径>
```

默认工具位置为 `%LOCALAPPDATA%\Aienie\tools\aisocialgame`。MySQL 入口显式准备官方包，只创建本次专用数据库；不接受业务 URL。结束时停止所属进程、确认端口释放并删除临时凭据和数据目录，保留脱敏日志。日常 L2 不启动 MySQL，对应外部测试跳过。

## 验证与证据

证据目录：`C:\Users\duwei\.codex\artifacts\AISocialGame\environment-gates-20260922`。

| 检查 | 结果与证据 |
|---|---|
| 真实 MySQL | 20 项通过：1 项三路径迁移、1 项四作用域准入、18 项游戏事务；mysql-final/reports、migration-results.json、admission-results.json |
| 数据库参数 | MySQL 8.0.45、utf8mb4/utf8mb4_unicode_ci、REPEATABLE-READ；仅本机临时测试实例 |
| 工具链回归 | 精确版本、错误版本、带空格路径、包校验失败、成功/失败环境恢复；toolchain-tests-final.log |
| 生命周期反例 | 占用端口拒绝；启动后故意失败仍清理进程及凭据，listenerRemaining=false；lifecycle.log |
| 前端目标环境 | Node 22.23.2 / pnpm 11.22.0，类型检查、构建及 58 项测试；frontend-first.log 及完整 L2 日志 |
| 最终标准门禁 | 后端 80 类、537 项：512 通过、25 项外部测试跳过，零失败/错误；其中新增 20 项 MySQL 测试已在上述显式运行全部通过。前端 58 项、独立 Python 14 项和 Java 指标通过；l2-complete.log、verification-summary.json、final-freeze |
| 验收脚本 | TypeScript 通过、Playwright --list 仅收集未执行；acceptance-types.log、acceptance-collection.log |

原始失败日志保留：首次调用准入测试使用 Mockito 不支持的 final stub，改为 mock Channel；环境恢复测试修正 Windows 空值与 null 的等价比较；首次独立 Java 指标命令按字符串误选旧 Jackson，改为版本排序。均未放宽生产规则。

本次只运行 mock 模型和测试上下文，没有启动游戏服务或真实验收。系统 Node 保持原版本；原调用账本前后哈希一致；无调用方状态变更、共享迁移、提交、推送或部署。

## 剩余门槛

本机 Windows MySQL 结果不代表共享 Linux 实例已迁移或备份恢复已验证。真实执行前仍须核实共享实例实际版本、配置、目标库、TLS/登录、原调用方及持久预算，准备迁移恢复和累计上限授权；然后运行冻结版本 180 个决策与六局并评分。MySQL 本机验证和 Node 版本不再列为未处理项。
