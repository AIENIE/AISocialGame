# 代码易读性与可维护性审计

## 审计基线与结论

- 审计日期：2026-09-26。
- 仓库：AISocialGame；基线提交：`8b89c02954810708458719dbbc00f3e8fb71b19b`，同时包含审计时工作区原有未提交变更。因此本文结论针对该工作区快照，不能直接归因于该提交。
- 范围：React/TypeScript 用户端和管理端、Spring Boot 控制器与业务服务、旧版 GameEngine 与 v2 GameRuleSet、JSON/DTO 契约、异常处理、单元及集成测试、Windows 验证入口、CI 和 SQL 发布迁移。
- 方法：遵循仓库 AGENTS.md 与 aienie-dev-workflow；CodeGraph 同步完成后通过 `node`、`affected` 核对符号与影响面，并直接阅读实现；配置、SQL、脚本及精确文本使用 `rg`。图谱读取时显示 586 个文件、12,307 个节点、31,548 条边，无待同步代码变更。
- 边界：本分项不修改业务代码，不启动或部署服务，不调用付费模型或外部数据库。全项目标准构建与测试由主审计统一执行，最终状态见同目录总报告与 `evidence/`；静态推导的问题不冒充运行时复现。

本次确认 5 项可维护性问题：2 项 P1、3 项 P2。最需要优先处理的是“发布升级清单与当前数据模型分叉”及“前端类型门禁未覆盖源码”。其余三项揭示了业务规则重复、查询接口隐藏写入副作用，以及错误消息被当作程序协议的问题。未因文件长、命名风格或单纯存在 `any` 就将其列为故障。

严重度：P1 为可能阻断关键发布流程或使核心质量门禁失效，应在对应发布路径启用前解决；P2 为有明确触发条件的行为偏差或维护风险，应排期修复。所有问题在本次交付中保持未修复。

| 编号 | 级别 | 主题 | 证据性质 |
|---|---|---|---|
| MNT-001 | P1 | 生产旧库升级计划漏掉 v2 增量迁移，测试另用一份清单 | 发布脚本、校验器、SQL 与测试交叉核对 |
| MNT-002 | P1 | 本地与 CI 的前端类型检查没有形成有效门禁 | 构建入口与 TypeScript 配置交叉核对 |
| MNT-003 | P2 | 前端自行判胜并重复累计成就，与后端结算语义分叉 | 结算调用链静态确认 |
| MNT-004 | P2 | 好友读取接口在布局渲染中写存储，写失败波及整个用户界面 | 布局→查询→写入异常链静态确认 |
| MNT-005 | P2 | 后端中文错误文案承担行为协议，v2 新文案已漏匹配 | 错误抛出点与前端分类/翻译规则交叉核对 |

## MNT-001：生产旧库升级计划与当前模型、测试清单分叉

**级别：P1；置信度：高。触发条件：未来经授权对旧库选择 `existing-legacy-schema` 计划。** 本项不是已发生的线上事故，也不表示生产入口现已启用。`scripts/ci/README.md:48-51` 明确说明，没有有效 v4 授权应保持冻结；本审计没有读取线上授权或执行生产迁移。

**证据与原因：**

- `scripts/ci/write-production-sql-ledger.py:28-32` 仅列 `schema.sql`、`20260519_performance_stability.sql`、`20260810_admin_totp_auth.sql`；`:57-59` 的旧库升级计划只选序号 2、3。
- 当前新增结构由 `backend/sql/20260912_game_realism_v2.sql:4-20` 增加 `rooms.host_user_id`、`rooms.private_config`、`game_states.version`；该文件还增加 AI 作业和预算表。`20260919_ai_turn_diagnostics.sql`、`20260922_milestone_closure.sql` 继续增加诊断及调用用量结构，却均未进入发布升级计划。
- `scripts/ci/write-production-migration-artifacts.py:11-20` 又维护一份固定制品清单；`scripts/ci/production-migration-executor:275-285` 与 `:309-327` 独立限制固定路径及 `len(entries) == 3`。只给 ledger 补文件还会被执行器拒绝，变更至少涉及数份清单。
- `backend/src/main/java/com/aisocialgame/migration/ProductionSocialMigrationMain.java:340-370` 只验证所选 SQL 中 `CREATE TABLE` 的表存在，再核验 `rooms` 的旧列/索引，没有验证当前应用完整模型。旧计划完成后可能报告 `current`，但仍缺 v2 需要的结构。
- `backend/src/test/java/com/aisocialgame/migration/ClosureMySqlMigrationTest.java:14` 自己列出另外三个新 SQL，未消费发布产物的计划；`ProductionSocialMigrationExternalMySqlTest.java:33-40` 只构造新库 baseline 计划。两个测试领域分别正确，并不能证明“实际发布的旧库升级计划”正确。
- README 的上线清单仍仅列前两份旧迁移（`README.md:70-96`），进一步扩大操作说明与实现的差异。

**影响：** 一个尚未手工应用 v2 迁移的旧库按受控发布计划升级后，可能在应用 schema validation 阶段失败，或在访问缺失结构时失败。根因是 schema、迁移清单、签名制品白名单、测试和文档没有共用版本化来源；当前签名和校验并不能弥补清单内容不完整。

**建议：** 建立一个受版本控制的迁移 manifest，由它生成 ledger、制品闭包与执行器预期集合；保留签名、路径及摘要验证，避免用放宽白名单绕过问题。增加完整的旧库→当前模型升级计划，并明确 baseline 是否可变、已应用历史摘要如何管理。让测试直接消费构建生成的真实计划，更新入口文档。

**验收标准：** 在经授权的隔离 MySQL 上，对新库、前 v2 旧库、部分迁移成功后中断的库执行实际发布产物中的计划；升级后与新库对比表、列、索引并运行应用 schema validation；复跑幂等且旧数据保留。增添新 SQL 而未加入计划时，离线合同测试必须失败。真实数据库验证应单独授权，不由本审计自动执行。

## MNT-002：前端类型检查门禁没有覆盖应用源码

**级别：P1；置信度：高。**

**证据与原因：**

- `scripts/windows/Build-Local.ps1:15` 执行 `tsc --noEmit`，使用默认根配置。
- `frontend/tsconfig.json:2-5` 是 `files: []` 的 solution 配置，只引用 `tsconfig.app.json` 和 `tsconfig.node.json`；普通 `tsc` 不会按构建模式遍历引用项目。该命令成功不能证明 `src` 被检查。
- `frontend/package.json:11-20` 没有 `typecheck` 脚本，`build` 仅为 `vite build`。`scripts/ci/aienie-ci-phase.sh:478` 仅在存在该脚本时才执行类型检查，因此 CI 也缺少对应检查。
- `frontend/tsconfig.app.json:18-23` 关闭 strict、隐式 any、未使用变量等检查；`frontend/eslint.config.js:26-31` 对显式 any 只警告。这些宽松策略不是本项故障本身，但不能补足漏掉的 TypeScript 检查。

工具语义已核对官方说明：[TypeScript project references](https://www.typescriptlang.org/docs/handbook/project-references.html) 说明普通 tsc 不自动构建引用项目；[Vite TypeScript 支持](https://vite.dev/guide/features.html#typescript) 说明构建转换不能替代独立类型检查。

**影响：** 开发者修改 DTO 字段、组件 props 或库 API 时，可以看到标准本地构建和 CI 构建通过，却没有得到 TypeScript 对应用源码的诊断。报告中不能将原命令的零退出码记为“应用类型检查通过”。

**建议：** 添加一个明确覆盖 app 与 node 配置的 `typecheck` 脚本，例如分别执行 `tsc --noEmit -p tsconfig.app.json` 和 `tsc --noEmit -p tsconfig.node.json`，并让标准 Windows 入口调用同一脚本；也可以正确配置 project references 后采用构建模式。先恢复真实门禁，再逐模块收紧类型，避免一次开启全库 strict 造成大量无关联修改。

**验收标准：** 在隔离的校验夹具中引入一个真实应用组件的错误 props 类型，标准本地和 CI 门禁均必须失败；移除错误后均通过。检查应覆盖 `src` 与 Vite 配置。本轮主审计执行的显式 `-p tsconfig.app.json` 检查首次异常结束（返回 -1、日志为空），随后一次重试正常退出 0，未报告类型错误，见 `evidence/frontend-typecheck-retry-status.txt`。重试证明本次源码通过当前 app 配置检查；它不改变标准入口未执行同一检查这一门禁缺口。未对源码植入错误进行负向门禁实验。

## MNT-003：前端复制结算逻辑，成就判胜错误且缺少持久化幂等

**级别：P2；置信度：高。**

**证据与原因：**

- `frontend/src/pages/games/shared/useRoomRuntime.ts:175-183` 用房间、轮次和日志数量拼接结算标识，只保存在组件 `useRef`；判胜公式是 `!!state.winner && !!me?.alive`。
- `frontend/src/services/v2Social.ts:197-219` 的 `applySettlement(userKey, didWin)` 每调用一次就累计一局，并可能增加胜场和连胜；没有接收 archiveId 或记录已处理结算。
- 服务端真正的结算依据是 `GameRuleSet.winningPlayerIds`（`backend/src/main/java/com/aisocialgame/engine/v2/GameRuleSet.java:63-65`），`V2GameService.java:299-302` 把该集合交给统计，`StatsService.java:34` 按成员关系判胜，与“是否存活”不是同一规则。
- 当前公共响应已经提供真实归档 ID（`V2GameService.java:422`），前端本地归档仍自己拼接另一个 ID（`frontend/src/services/v2Social.ts:257`），使后续统一结算与回放身份更困难。
- `frontend/src/pages/games/shared/useRoomRuntime.test.tsx:15` 把游戏状态 hook 替换成不含结算状态的 mock；当前测试主体验证 AI 入座，不覆盖这条跨层规则。

**具体触发：** 玩家存活但所在阵营输掉比赛时被记为胜利；已出局但阵营获胜的玩家被记为失败。退出结算页再返回或刷新后，`useRef` 重置，同一局再次增加局数。以上均可由代码路径直接推出，本分项未启动浏览器进行复现。

**建议：** 服务端明确输出当前玩家结算结果/获胜玩家集合与稳定 archiveId，由前端消费。即使保留文档所述“本地成就”定位，也应按 `userId + archiveId` 保存处理记录，不能只在组件生命周期中去重；展示组件不要重建游戏规则。

**验收标准：** 覆盖存活败方、出局胜方、同一结算重复推送、卸载重挂载与刷新；成就局数只增加一次，胜负与服务端统计一致。增加跨层合同夹具而非只 mock 一个自认为正确的 `didWin`。

## MNT-004：读取好友计数会写存储，存储异常穿透整个布局

**级别：P2；置信度：高。**

**证据与原因：**

- `frontend/src/components/layout/MainLayout.tsx:51` 在 render 中调用 `friendApi.getPanelData(userKey)` 获取好友请求数量。
- `frontend/src/services/v2Social.ts:114-118` 名称为读取的方法，无论数据是否变化都调用 `saveJson`；`:41-42` 的 `localStorage.setItem` 没有错误处理。
- 读取端 `loadJson`（`:31-38`）虽然捕获存储/解析异常并返回 fallback，但随后查询方法马上再次写入，故该 fallback 不能使拒绝存储场景正常降级。
- `frontend/src/main.tsx:30-32` 的 UserErrorBoundary 包住整个 UserApp。这能兜底显示错误页，但不能把好友模块的存储错误限制在好友模块。

**影响：** 浏览器禁用存储，或在需新增/改变记录时发生配额不足，都会令获取导航栏计数的过程抛错，从而切换整个用户应用到错误页。即使用户没有打开好友面板，也会触发该依赖。修改布局渲染频率还会隐式改变写入频率，难以局部推理。

**建议：** 将本地存储封装为明确的 repository：查询无持久化副作用；只在用户操作或初始化阶段写入，并通过可处理的结果类型报告失败。校验 JSON 的结构/版本，在好友或本地记录模块内降级与提示，不影响游戏、钱包和导航。这里不要求把文档中明确定位为本地功能的所有社交模块立即改成服务端。

**验收标准：** 在测试中让 `Storage.prototype.setItem` 抛出 `QuotaExceededError`/`SecurityError`，并单独测试读取失败和合法 JSON 但形状错误的旧数据；主布局仍可呈现、用户可进入游戏，仅相应本地功能显示失败。重复 render 不应调用 setItem。

## MNT-005：错误文案与行为协议耦合，v2 文案已出现分类漂移

**级别：P2；置信度：高。**

**证据与原因：**

- `backend/src/main/java/com/aisocialgame/engine/v2/RuleSupport.java:109-110` 的 `bad/require` 只构造 HTTP 状态和中文消息，不提供稳定业务 code。
- `frontend/src/pages/games/shared/useRoomRuntime.ts:14` 与 `:107-118` 用中文子串决定错误是 info 还是 error；`frontend/src/i18n/errors.ts:9-21` 再用另一套中文正则选择翻译。
- 当前 `UndercoverRuleSet.java:140` 返回“当前不能执行这个动作”，但前端阶段错误只匹配“当前阶段不支持/阶段不支持/该阶段不允许”。该 v2 错误无法进入原有阶段提示和可恢复分类，英文/繁体界面只能落到调用方通用失败文案。
- `GlobalExceptionHandler.java:28-30` 已支持输出 code；`frontend/src/services/apiError.ts:3-7` 也声明 code，但常规 `getApiErrorMessage` 只提取文本。管理端二次确认已利用 `ADMIN_OPERATION_PROOF_REQUIRED`（`frontend/src/services/api.ts:313`），说明项目已有可复用的稳定码模式。

**影响：** 后端改中文措辞、本地化或引入新规则时，会无类型错误地改变前端行为与用户恢复指引。维护者必须同时知道两套文案匹配表；新增游戏更容易漏掉恢复路径。此项影响错误分类和提示，不夸大为后端越权或操作校验失效。

**建议：** 为 phase changed、illegal action、already voted、room full 等定义稳定错误码和必要的结构化参数；前端按 code 决定恢复策略与翻译，中文 message 仅作兼容/诊断 fallback。沿用现有 ApiException/handler 通路，无需为此引入额外异常框架。

**验收标准：** 对同一个 code 改变中文 message 后，前端 toast 级别、翻译 key 和刷新行为保持不变；三款规则分别用真实后端错误样例覆盖阶段过期、已投票、非法动作；未知 code 给出可理解的统一兜底并保留 requestId 供排障。

## 已审阅的架构与测试评价

| 领域 | 已存在的有效设计 | 仍需明确的维护边界 |
|---|---|---|
| 旧版/v2 路由 | `GamePlayService` 按状态版本路由，旧版测试显式关闭 v2；`GameEngineRegistry` 与 `V2GameService` 均拒绝重复玩法 ID | 不能把旧引擎直接视为死代码删除。新增兼容修复应覆盖旧局继续、v2 新局及三种旧动作入口；本文未确认路由本身存在故障 |
| v2 游戏模块 | `GameRuleSet` 分离规则与网络/持久化；`GameMetadataV2` 从规则 definition 获取元数据；有 PluginClosureTest、v2 隐私及幂等/期限测试 | `V2GameService` 同时协调持久化、事件、AI 任务、记忆与结算；后续优先按事务边界提取协作者，避免以拆短文件为目标的大改 |
| 状态与契约 | DTO、合法动作解析和请求幂等标识已经存在；前端 action 有单独测试 | `GameState.data`、前端 `GameState.extra` 仍是动态 map；持久化 JSON 的键与版本应有显式 schema/兼容夹具。不能仅凭宽松 `RuleSupport.map/number` 默认值推断旧数据一定兼容 |
| 异常与日志 | GlobalExceptionHandler 对一般异常返回统一消息和 requestId，JSON converter 对损坏内容抛出明确异常 | 游戏业务错误缺少稳定 code（MNT-005）；生产迁移入口 `ProductionSocialMigrationMain.java:116-118` 把全部异常统一输出为 rejected，后续可增加不含秘密的阶段/错误码以减少定位成本 |
| 前端共享层 | useGameEngine、useRoomRuntime、V2ActionPanel 复用请求、幂等和合法动作逻辑；用户/管理端按入口分包 | 成就与本地存储副作用混入共享 runtime/layout（MNT-003、004）；已文档化的本地社交能力不等于服务端持久化，不应把二者统计混作一份权威结果 |
| 测试 | 有规则单测、H2 集成、真实 MySQL 可选套件、隐私和预算防线测试；不是无测试项目 | 测试入口成功只覆盖实际执行的测试。真实 MySQL/外部服务的条件跳过不能算通过；本轮最明显的缺口是发布清单与测试清单不一致，以及跨层结算和浏览器存储失败场景 |
| CI/文档/迁移 | 标准 Windows 入口、锁文件、离线依赖合同及生产签名闭包已有组织基础；模块文档标明当前 v2 与旧说明 | 恢复真正的 TypeScript 门禁，合并迁移真相来源，并同步 README。历史验收记录不能代替本轮验证结果 |

## 建议实施顺序与验收范围

1. 先修 MNT-002，建立可实际阻止源码类型错误的门禁；再基于诊断修复类型问题，不批量把 `any` 替换成断言。
2. 在任何旧库生产升级获准前闭环 MNT-001，使发布产物、数据库模型和迁移验证共享清单；隔离 MySQL 验证另行授权。
3. 以稳定 archiveId 和服务端结算结果修 MNT-003，同时补组件重挂载和跨层合同测试。
4. 修 MNT-004 的存储边界并补拒绝存储测试；最后将 MNT-005 的错误码逐游戏迁移，保留兼容 fallback。

以上建议为审计后的整改工作，不属于本轮已经完成的修复。代码安全和性能项分别见同目录对应报告；本报告中的类型、迁移与业务语义问题不得用“单测全绿”替代逐项验收。
