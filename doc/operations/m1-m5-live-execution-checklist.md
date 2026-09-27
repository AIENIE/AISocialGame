# M1～M5 真实执行清单（尚未执行）

当前生成版本已更新为 **social-v2.8 / inputFormatVersion=3 / memoryFormatVersion=4**，见[正常交流优化报告](../test/ai-conversation-20260922.md)。本次仅离线工程验证。新的构建摘要覆盖 `.txt` 提示及评阅尺度；必须使用重新生成并绑定本批源码/构建的清单，不能沿用 v2.7 或输入 v2 的批准材料。48 份 `conversation-v1` mock 输入不是原 180 个真实样本的替代，也不增加已授权真实请求额度。

日期：2026-09-22。依据用户批准计划的“真实执行门槛与预算”：工程阶段不自动应用共享迁移或提高累计预算。以下清单是下一阶段的审查材料，不是授权凭据。

本批只读准备已完成，详见[入口修复与预检报告](../test/readonly-preflight-20260922.md)。原账本当前采集为 74；矩阵 13306 与现有验收配置 23306 冲突，数据库未连接，持久 consumed 及 caller 实时状态仍未知。不得仅修改端口后试连，也不得把历史 OFFLINE 当成当前读回。

另有已批准方案的[96 样本当前版本交流验证](../test/ai-conversation-validation-20260922.md)，工程门禁已通过。使用 `Test-AiConversation.ps1` 和独立 conversation-validation-v1 清单，不能传入本清单原 180 项入口。它使用隔离状态，不依赖共享库迁移、玩家登录或游戏服务；并不关闭这些业务运行缺口。当前申请为先导最多 24 新请求、全部最多 192（含先导），原 74 对应累计 98/266；仍未授权。caller 33 的 HMAC 标识为 aisocialgame-realism-v2-20260912，真实启用状态为 ACTIVE，结束须 OFFLINE 读回。

独立入口顺序：Preflight → 核实原 caller/模型与 TLS → 获得绑定清单、构建、原账本及有效期的明确授权 → 按授权启用原 caller、保存 15 分钟内的脱敏读回证据 → Pilot → 对精确证据 SHA-256 做逐例评阅 → Remaining。任何中断保留账本和样本，不重跑已收费样本；结束按原 caller 停用清单收口。授权草案不能作为正式 grant。没有正常 96 项完整覆盖时只报告已观察的正常质量，不宣称专项或 L4 通过。

后续实际执行记录：用户已授权旧冻结批次累计 266，完成先导 12 项后门禁失败，Remaining 未运行。原账本已到 **91**（新增 17）；caller 33 已在 2026-09-22 09:48:53 UTC 读回 OFFLINE。协议示例修复产生独立新构建，不能把旧先导拼入新清单；若重新完整验证，新 96 决策的理论新增上限仍为 192，按当前 91 应重新核算至 **283**，超过原批准 266，不能自动续跑或扩额。余额 175 是原批准上限内的未用请求数，不代表先导门槛已经解除。六局和原 180 项仍各自待验。

新的材料入口：`scripts/windows/Test-ClosurePreflight.ps1 -EnvironmentFile <原验收环境文件> -BudgetFile <原账本> -ManifestFile <本批冻结清单> -AccountsFile <原账号材料> -EvidenceDirectory <新的仓库外目录>`。读取现有配置，不创建登录会话。输出四态检查、迁移差异与未授权申请草案；实际执行必须重新读取易变状态。

后续先核实 23306/13306 的主机与转发关系，形成有效目标证据；补齐显式本地运行平面及 deepseek-flash 配置的核实。然后按授权范围完成现有 caller 读取、共享结构/预算 SELECT、备份恢复确认及登录。`UNAUTHORIZED_EXECUTION_PROPOSAL` 不能传给执行入口充当批准文件。

## 1. 先完成无付费门槛

- [x] 当前源码完整前后端 L2、类型检查、构建、独立指标测试通过，见 [收尾报告](../test/m1-m5-closure-20260922.md)。
- [x] 180 个固定 ID、规则驱动连续序列、评分尺度及失败停止逻辑已准备。
- [x] 原 comparison-call-budget.jsonl 只读核实为 74 次；未修改原账本。
- [x] 本机原生 MySQL 8.0.45 已验证新库、旧库升级、已有 v2 升级、重复执行、数据保留及并发/事务；见[环境门禁报告](../test/environment-gates-20260922.md)。共享实例实际配置仍须运行前核实。
- [x] Node 22.23.2 / pnpm 11.22.0 目标环境已验证，标准脚本统一检查版本；不使用系统 Node 24 的历史结果代替。
- [ ] 只读核实当前标准环境矩阵、TLS 信任链、普通用户正常登录、原 caller 标识/状态、目标数据库及持久 budget run ID/已用次数/累计上限。记录脱敏主机、库名和时区配置，凭据仅保留仓库外。
- [ ] 将明确的迁移目标、备份位置、原 caller ID、两个累计预算上限、批准人/授权引用/有效期与源码指纹补入仓库外执行授权文件。不能猜测旧对局 105/210 是实时余额。

## 2. 数据库验证、应用与恢复

顺序固定；不改写旧房间或历史验收证据：

1. 基础库从 `backend/sql/schema.sql` 建立；若当前库缺少 v2 表，先执行 `backend/sql/20260912_game_realism_v2.sql`。
2. 执行 `backend/sql/20260919_ai_turn_diagnostics.sql`，增加 ai_turn_jobs 可空 diagnostics。
3. 执行 `backend/sql/20260922_milestone_closure.sql`，增加 ai_call_usage。首次/重复执行都应保留旧行、旧归档及原预算行，不清空任何账本。

隔离验证应使用与目标相同的 MySQL 主版本和字符集，记录版本/SQL hash。分别验证：空库完整 schema；旧版 schema 加种子行后依序升级；全部增量重跑两次；原记录数量及哈希不变；诊断字段允许 NULL；调用记录保存 NULL 用量并支持锁/时间窗查询。不能连接共享生产库完成这个测试。

共享目标应用前停止该应用写入并保存可恢复的一致备份，记录恢复演练和目标库确认。通过现有入口 `Migrate-GameRealismV2.ps1 -Migration RulesV2|Diagnostics|Closure` 先只读 inspect；实际 DDL 只有经批准加 `-Apply` 后执行。环境文件使用已核实仓库外路径，不在命令/日志打印密码。

恢复优先停止新版应用并回退应用版本，新增可空列/独立表可保留；需要数据库恢复时使用批准的备份和重放边界，不能在运行中 DROP 新表或删除调用记录来“恢复额度”。备份早于真实请求时，恢复库也必须保留并核对之后的累计请求记录，禁止重置额度。

## 3. 预算与原调用方

| 账本 | 本轮已知 | 本批上限需求 | 执行前必要条件 |
|---|---|---|---|
| 原离散对照 journal | 74 已预留，历史上限 90 | 180 决策 × 最多 2 次 = 360 新请求；累计至少 434 | 明确授权 cumulativeComparisonLimit，继续同一文件和原 caller |
| 对局持久预算 | 仅历史 105/210，当前未知 | 六局及昵称另计；无法用 360 代替 | 新读回 run ID/已用量，提出并批准 cumulativeLiveLimit；不新建 run 绕过旧额度 |

额度是最大请求数，不是自动消费目标或货币余额。实际一次成功通常只用一次请求。头 12 样本最坏 24，剩余 168 最坏 336；先导失败不得直接扩大调用。若执行前已用量变化，重新计算 cumulativeConsumed + headroom，不能沿用过期的 434 数字。

仓库外批准文件必须包含：`approvedBy`、`approvalReference`、UTC `expiresAt`、`model=deepseek-flash`、`callerId`、`sourceFingerprint`、`comparisonLedger`、`cumulativeComparisonLimit`；对局另需 `liveBudgetRunId`、`cumulativeLiveLimit`。批准值取自用户明确授权，不由代理自行伪造。读取原 caller 后才允许把它 ONLINE；不新建 caller、不换模型、不充值、不改 HMAC 权限。

## 4. 冻结与运行

本批统一证据采用 `evaluationSchemaVersion=2`、`evaluationSetVersion=closure-v2`，详见[可靠性修复报告](../test/acceptance-reliability-20260922.md)。清单、决策、评审、六局和运行指标必须共享 batchId/sourceFingerprint/buildId 及各格式版本。批准文件亦必须绑定 batchId/buildId。旧格式只能历史查看，不能计为新版通过。

标准 Build-Local 后端构建生成内嵌标识与 `backend/target/closure-build-manifest.json`，后者含 JAR SHA-256；执行前 `closure_identity.py verify` 必须通过。源码、知识包、人格、夹具或判定脚本发生变化后重新构建和冻结，禁止复用旧标识。归档冻结清单及构建清单至仓库外证据目录；批次只使用同一份清单，不在运行中重新生成。

1. 保存 L2 日志、源码/SQL/脚本 hash、知识包及 Persona 版本。重新生成 `m1-m5-evaluation-manifest.json` 并与授权指纹比对；普通账号通过正式 SSO 登录得到会话，不使用 header 伪造身份。
2. `Test-MilestoneClosure.ps1 -Phase Preflight -BudgetFile <原外部账本> -ManifestFile <冻结清单>` 只读验证；未获额度只运行此阶段。
3. 使用同一脚本 `-Phase Pilot`，提供已核实 `EnvironmentFile`、`ApprovalFile` 和新的仓库外 `EvidenceFile`。只执行三款玩法各四 Persona 的首个代表样本，共 12 个，计入正式样本。
4. 按冻结尺度逐例检查先导。审查文件保存 PASS/失败、样本引用、evidenceFile 及其 SHA-256；指纹须一致。资源/鉴权/预算/越权错误立即停止扩大。普通语义质量不达门槛也不继续消费其余样本。
5. `-Phase Remaining` 需提供 `PilotEvidenceFile`、`PilotReviewFile`，完成剩余 108 单情境 +48 连续步骤 +12 主持样例。连续步骤用真实规则提交动作，再构造下一观察。保留所有失败与 fallback，输出新文件，不覆盖先导原文件。
6. 六局 API 验收使用 `Test-GameRealismV2Live.ps1`，传 `TokensFile`、`EvidenceFile`、`ClosureApprovalFile`、`ClosureManifestFile`、`SoupAnswerFile`。六种配置各从开局到结算；Closure 模式不允许 ResumeEvidenceFile 拼接历史。汤底验收答案文件在运行前由审查者冻结，记录 hash；普通玩家提交答案必须 SOLVED，不能 REVEAL。此脚本是普通账号 API 驱动的流程验收，不是独立人工盲评。
7. 六局在同一冻结版本执行，为 M1～M5 共用证据；任何修复造成版本变化后，旧数据继续保留，新版重新覆盖全部要求。

只有本地验收阶段确有需要且运行门槛通过后，才按 Windows 受管入口启动应用。前后端端口为 11030/11031，本地入口 `localsocialgame.testhut.top`；公共服务地址必须从当前矩阵核实，不根据这份文档固定历史地址。

## 5. 判定与停止

先导与最终评审都必须保存实际决策文件的 evidenceSha256，并使用同一严格判定器。三款玩法四项人格行为逐项填写 `PASS / FAIL / INSUFFICIENT_EVIDENCE`、非空 assessment 和结构化 references；至少引用同玩法同情境的两个不同 Persona，连续样本须同一步骤。引用使用 sampleId 加 `/decision/...` pointer 或观察中的 eventId，手写 PASS 或任意文字不能代替完整证据。

六局的 archiveId 必须来自实际公开响应且局内稳定；指标按 instanceId/archiveId 与 jobId 核对，保存六局证据文件 SHA-256、批次和集合。任一缺失、错配、未知 fallback、重复/孤立 trace 都阻止最终通过。失败/无响应不计完成，合成测试不能成为真实验收结果。

`Measure-MilestoneClosure.ps1` 接收 Manifest/Evidence/Review，六局完成后再传 Games/Runtime。没有六局与运行指标时只能判断离散评测，L4Passed 必为 false。

逐项检查：120/48/12 无缺失；三款各 1 真人和 3 真人的六局完整；两局汤 SOLVED；单情境与连续序列总体及四 Persona 的四项均值各 ≥4；两组 fallback 各 ≤10%；主持 fallback 单列；无已确认越权/伪造系统裁决/非法提交；终态诊断和成功任务 trace 关联 100%；无 QUEUED/RUNNING 残留。阶段延迟、字节、实际 token、缺失样本均报告；不能用输入体积替代速度结论。

遇资源、鉴权、预算耗尽或越权立即停止新增调用并保存脱敏原因和仓库外原始证据。禁止重置账本、换 caller、转移预算、删除失败样本、在新版本中拼接旧局。

## 6. 无论成败均执行清理

- 停止调度新请求，处理在途/迟到任务并保存最终诊断，不伪造成功 trace。
- 停用原 caller 并读回 **OFFLINE**，记录时间及脱敏标识；状态写入失败时明确报告，不宣称完成。
- 停止本次受管本地服务；仅处理当前任务拥有的进程。复核 11030/11031 无监听，保留日志。
- 比对前后账本与预算，写回统一结果表；原始证据只追加，凭据、主持秘密和原文不入仓库。

本轮没有启动服务或更改 caller，因此未执行上述有状态操作，也未宣称已读回 OFFLINE。下一执行阶段必须按本清单完成并记录。
