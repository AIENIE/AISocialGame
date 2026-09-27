# 审计验证与证据记录

日期：2026-09-26。验证由主审计统一执行，未使用旧测试报告冒充本轮结果。运行控制器为 Windows PowerShell 7.6.6，平台 `Win32NT`。本地标准入口使用仓库指定的 Node 22.23.2 / pnpm 11.22.0。

## 本轮实际结果

| 检查 | 命令/入口 | 结果 | 证据 |
| --- | --- | --- | --- |
| 索引 | 设置 `CODEGRAPH_DIR=.codegraph-win`，运行 `codegraph status --json <repo>` 及 `codegraph sync <repo>` | 路径匹配；同步成功，14 个变更文件；随后按符号追踪并直接读源码 | 会话工具记录；源码指纹见下文 |
| 系统矩阵 | `Test-AienieSystemMatrix.ps1`，指定 operations/integration 文档及 diagrams | 退出 0，37 个组件，生成文档与图表校验通过 | [system-matrix.log](evidence/system-matrix.log) |
| 标准构建及本地测试 | `./scripts/windows/Test-Local.ps1 -Level L2` | 退出 0；包含后端 package、前端 frozen install、根配置 tsc、Vite build、Maven test、Vitest，以及入口和 Node 辅助测试 | [local-l2.log](evidence/local-l2.log) |
| 后端测试统计 | 本轮生成的 Surefire XML；仅选本次开始后写入的报告 | 86 个套件、550 项；524 通过、26 跳过、0 failure、0 error | [backend-test-summary.json](evidence/backend-test-summary.json) |
| 前端单元测试 | 由 L2 调用 Vitest | 9 个文件、58 项全部通过 | `local-l2.log` 末尾 |
| 应用源码类型检查 | 通过 `Invoke-WithProjectNode` 执行 `corepack.cmd pnpm@11.22.0 --dir frontend exec tsc --noEmit -p tsconfig.app.json` | 第一次返回 -1 且无诊断，原因未定；重试一次退出 0、无类型错误。未执行 node 配置的独立检查 | [首次日志](evidence/frontend-typecheck.log)、[重试日志](evidence/frontend-typecheck-retry.log)、[重试退出状态](evidence/frontend-typecheck-retry-status.txt) |
| 前端 lint | 同一项目 Node 包装器内执行 `pnpm run lint` | 退出 0，0 errors、110 warnings；没有自动修复 | [frontend-lint.log](evidence/frontend-lint.log) |
| 前端依赖 | `pnpm audit --json` | 退出 1，20 条公告记录；去重 17 个 GHSA、13 个包，详见 SEC-007 的可达性分析 | [security-dependencies.json](security-dependencies.json) |
| 后端依赖 | 离线 Maven dependency tree，限定 gRPC/protobuf | 成功确认 `grpc-protobuf:1.63.0 → protobuf-java:3.25.1`；不是全依赖 SCA | [security-maven-dependencies.txt](security-maven-dependencies.txt) |

根配置的 `tsc --noEmit` 不等于检查应用源码，见 MNT-002。应用显式类型检查重试通过，并没有修复标准入口的门禁缺口。空类型检查日志表示无文本输出，退出状态单独记录，不能用日志文件为空推断成功。

日志中的模拟错误、预期拒绝或测试 Spring 上下文日志，不等于测试失败；以当前 Surefire 结果、Vitest 汇总和进程退出码判断。`local-l2.log` 的 Vite 产物信息是真实构建结果，但本轮受本机环境影响的构建耗时不作为应用性能基准。

## 外部测试跳过

为避免普通本地测试误触真实系统，在本次命令进程中移除了真实 AI、SSO bootstrap 和 MySQL 专项的启用环境开关，没有修改用户持久环境或 `env.local`。26 个跳过项来自：

- `LocalRealismLoginBootstrapTest`、`AiDecisionRealIntegrationTest`。
- `ClosureMySqlGameIntegrationTest`（18 项）、`ClosureMySqlMigrationTest`、`ClosureMySqlAdmissionTest`。
- `ProductionSocialMigrationExternalMySqlTest`。
- `AiRealismComparisonIntegrationTest`、`ConversationValidationRealIntegrationTest`、`MilestoneClosureRealIntegrationTest`。

这些是**未执行**，不能算通过。测试中的 Spring mock/H2 上下文不代表部署了可对外服务的应用。未启动实际应用、浏览器 E2E、MySQL 迁移、线上负载或付费请求；各专项报告的验收清单是整改后的后续工作。

## 基线及复核

- HEAD：`8b89c02954810708458719dbbc00f3e8fb71b19b`，工作区原本有大量修改和未跟踪文件。
- [source-manifest.json](evidence/source-manifest.json)：540 个实现、SQL 和脚本文件的路径与 SHA-256；不含文件内容及真实环境机密，不是完整 Git 快照或逐行审计覆盖率。
- [source-verification.json](evidence/source-verification.json)：交付前再次比较上述指纹；如有变化，应以该列表为准重新核对受影响结论。
- 主审独立核对了 STOMP 路由/拦截器、令牌签发认证、AI 调用与计费先后、房间锁与调度、回放全扫描、统计增量、SSE permit 竞态、迁移清单、前端结算与存储异常链。其余专项详细证据由对应审计者记录。

现有测试证明当前覆盖的行为可通过本地验证，不能证明新发现的越权、故障隔离、规模增长和跨层一致性场景已经受到保护。
