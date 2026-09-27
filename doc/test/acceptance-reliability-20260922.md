# 验收判定与连续评测可靠性修复

日期：2026-09-22。本批仅离线验证，工程修复完成，**总体 L4 未通过**。既有真实失败证据和旧格式文件保留，不计入新版本覆盖。

## 修复结果

| 问题 | 实现 | 验证 |
|---|---|---|
| fallback 缺失、类型错误或状态冲突可能被计作正常 | 必须是布尔值，GENERATED=false、LOCAL_FALLBACK=true；完成数只计有效状态组合，计划数、已知/未知数分别报告；未知不能缩小门槛分母 | 缺失、null、数字、字符串、状态冲突、无响应均拒绝；数值拒绝布尔、非有限数和越界 |
| 版本、批次及同房多局可能混算 | schema=2、closure-v2；统一 batchId/sourceFingerprint/buildId；源码摘要包含应用、知识、人格、夹具和判定脚本；JAR 独立 SHA-256；任务及 trace 保存内部构建标识 | 源码/脚本/JAR 变化、批次或归档错配、重复/孤立 trace、成功任务缺 trace、零分母和伪造数量均拒绝 |
| 人格差异只有自由文本，没有通过结论 | 三款玩法四项行为分别要求 PASS、非空 assessment、同玩法同情境两个不同 Persona 的有效引用；连续引用对齐步骤 | FAIL、INSUFFICIENT_EVIDENCE、缺结论、错玩法/情境及不存在的引用不能通过 |
| 四次决策没有保证经过完整承接过程 | 新冻结规则初始状态；真实规则提交发言、质询、投票及固定主持裁决；每步记录阶段、轮次/周期、动作、来源及事件依据 | 48 步真实规则离线序列；改变/坚持判断、弃票、承诺未兑现和合法兜底均覆盖；第三步动作进入第四步历史 |

卧底连续夹具按用户确认采用**七人、两卧底**，受评 AI 为平民。原因是现有生产规则六人最多一名卧底；本批没有修改人数规则。狼人杀六人、受评 AI 为村民，非计分夜间动作合法跳过。海龟汤主持只使用现有事实包校验的固定裁决，未覆盖问题保持未知。样本总数仍为 120＋48＋12。

所有脚本推进与模型决策分开记录。合法 PASS/SKIP 可能不产生事件，此时通过实际提交后生成的 recentDecisions 核对后续承接，不伪造事件。未到达或提交失败保留不完整状态，不重抽样掩盖失败。

## 证据契约与使用

清单、决策、评审、六局文件和运行报告共有：evaluationSchemaVersion、evaluationSetVersion、batchId、sourceFingerprint、buildId、promptVersion、inputFormatVersion、memoryFormatVersion、evidenceKind。样本明确记录 id/group/gameId/personaId/scenarioId/sequenceId/stepId，不从 ID 前缀推断归属。

评审的 evidenceSha256 必须对应实际决策文件。六局保存公开响应中的 archiveId，同一局变化即失败。运行指标按 instanceId 查询任务，并核对 trace.instanceId/jobId；roomId 仅辅助过滤。运行报告保存六局文件 SHA-256 及完整对局集合。未知旧任务标识不补写；旧数据可查看，不能满足本次门槛。

人格评审引用示例：`{"sampleId":"冻结样本ID","pointer":"/decision/action/content"}` 或 `{"sampleId":"冻结样本ID","eventId":"观察内事件ID"}`。自由文字不能代替可定位引用。工具验证显式结论及引用完整性，语义评分仍由编码代理负责，不是独立人工盲评，也不是自动语义裁判。

先导与最终判定共用 closure_metrics.py。手写 PASS 不能绕过字段和引用校验。报告分别输出 evaluationPassed 和 L4Passed；缺六局/runtime 时 L4=false。SYNTHETIC_TEST 即使通过算法，也不能输出真实评测或 L4 通过。单元测试含完整正向合成样例，证明判定器并非永远拒绝。

## 本批验证

仓库外证据：`C:\Users\duwei\.codex\artifacts\AISocialGame\acceptance-reliability-20260922`。

| 检查 | 结果/证据 |
|---|---|
| 受影响后端回归 | 通过，affected.log；早期失败日志保留于此前收尾证据目录 |
| 标准后端完整 L2（含构建） | 76 类、516 项，511 通过、5 项外部测试跳过，零失败/错误；backend-l2-verified.log |
| Python 独立判定与构建标识测试 | 14 项通过；含 Java 实际导出 48 步证据的消费验证，metrics-python-final.log |
| 独立 Java 指标测试 | 通过；mock JDBC 验证同房间旧局排除、错配保留供拒绝、未知归属单列；metrics-java.log |
| 验收脚本离线检查 | 修改的 Playwright 脚本 TypeScript 通过，--list 仅收集 1 项未运行；5 个 PowerShell 入口语法通过 |
| 最终报告入口 | Measure-MilestoneClosure 合成完整证据实际执行成功，finalAlgorithmPassed=true、evaluationPassed=false、L4Passed=false；synthetic-cli/report.json |
| 构建及预算 | 构建资源、源码与 JAR hash 关联校验通过；原账本只读 74 次，前后 hash 一致；budget-preflight.json |

未调用真实 RPC，未启用调用方，未更改账本或预算，未启动真实验收服务，未应用共享迁移，未提交、推送或部署。合成证据仅用于算法测试，不能作为里程碑真实验收材料。

复跑时保留一次事务测试失败记录（backend-l2-final.log）：承诺文本直接拼接随机 UUID，可能触发数字隐私审核并丢弃动作。夹具已改用规则支持的座位号，结构化目标 ID 不变；受影响回归见 affected-final.log。未为使测试通过而放宽生产审核规则。

## 剩余事项

后续更新：以下第 1、2 项已由[环境门禁批次](environment-gates-20260922.md)完成；第 3、4 项仍待执行。本节保留当时交付的待办记录。

1. 隔离真实 MySQL 新库、旧库升级、重复执行和数据保留验证；H2 不能代替。
2. 统一并验证 Node 22.23.2；当前 Node 24.13.0 的通过结果不代表目标版本通过。
3. 按执行清单核实环境、TLS、登录、数据库、原调用方与实时持久预算，取得明确累计上限授权后再运行真实 180 个决策和六局。
4. 冻结版本上的真实语义、人格差异、fallback 和六局成功解题门槛仍待完成；本批修复不改变 L4 未通过状态。
