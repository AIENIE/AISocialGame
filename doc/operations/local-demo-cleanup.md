# 本地演示数据清理

运行入口为 PowerShell 7：

```powershell
./scripts/windows/Cleanup-LocalDemoData.ps1 -Mode dry-run
./scripts/windows/Cleanup-LocalDemoData.ps1 -Mode apply
./scripts/windows/tests/Test-DemoDataCleanup.ps1
```

配置沿用本地私有 secret 与 config-pair 的拆分；可通过 `-EnvironmentFile` 指定既有私有文件。工具先执行系统矩阵验证，再核对 `windows-local`、本地 MySQL 入口及 `aisocialgame` 数据库。数据库凭据只进入维护子进程环境，不进入参数或输出。工具不启动应用服务。

`scripts/windows/support/demo-seed-manifest.json` 保存原始种子的明确 ID、内容和配置。识别范围为三个帖子、两个房间、五个榜单记录、两个 AI trace、两份原始 Persona 记忆和两个兑换码。该文件服务于历史数据识别；生成器仅存在于显式导入的测试 fixture，运行时启用旧 `app.demo-seed.enabled` 参数也不会生成样例。

清理执行采用 SERIALIZABLE 事务和行/关联范围锁，要求 InnoDB。逐项比对原始内容，并检查更新时间、版本、真实点赞、玩家身份、房间/回放/状态关联、JSON 玩家快照、trace/记忆关联和数据库外键。内容变动、已审核记忆或业务关联会列为 `skip`。删除只使用已核对的记录主键，不使用名称前缀或宽泛匹配。

兑换码存在使用计数、兑换记录、账本引用或外键引用时，只将原始演示码停用；已停用记录显示 `keep`。未使用且未修改的原始码可删除。账户、账本和兑换记录均不进行写入。

JSON 报告区分 `plannedDeletes`/`plannedDisables` 与实际 `deleted`/`disabled`，逐条包含主键、动作和原因。dry-run 回滚事务，apply 提交；任一写入失败整体回滚。重复执行不会重复删除或调整余额。工具不自动创建备份，也不访问预发布或生产。

验收覆盖精确匹配、类似 demo 名称的真实内容保护、真实点赞与玩家保护、审核记忆保护、非主键外键引用、已用码停用、账务余额不变、事务回滚、dry-run 和重复 apply。
