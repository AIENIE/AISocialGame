# AI 调用预算与积分托管

默认 `app.ai.budget-enabled=false`。只有兼容 ai-service、经过核验的路由声明、项目积分表迁移及有效系统用户配置齐备后才能启用。游戏与 HTTP Chat 共用预算客户端；图片、Embeddings、OCR 保持关闭。现有请求次数限制和安全准入仍生效。

每个逻辑请求先调用 PrepareCallBudget，再锁定用户积分账号，将最高 token 额度转入 ai_credit_reservations。临时积分优先，分批保存原到期日。请求只有取得新预留记录的执行者可以发送；并发或重放只查询状态。预算凭据不写日志。

网关成功状态必须携带权威 usage 和已提交结果；客户端用 GetBudgetCallStatus 结算，扣实际 token 并释放差额。临时差额写入 credit_temp_lots，保持原到期日，与后续签到分离；过期差额只记 EXPIRE。未知状态、查询失败、取消或超时保留 HELD，不自动重新推理。后台每 30 秒最多查询 5 条待对账记录，关闭新调用后仍继续对账。供应商已发送但没有权威 usage 的记录需要后续核验供应商证据，不能由管理员直接冲正预留流水。

## 配置与发布顺序

1. 发布兼容 ai-service，执行其新增 prepared_ai_calls 迁移。旧调用方行为不变。
2. 通过管理员只读 `/api/admin/budgets/routes/{apiId}` 核对实际路由指纹，并按照 canonical bounded-budget 契约补充供应商输入硬上限、包含隐藏推理的输出上限及来源证据。空声明不发付费请求。
3. 本地使用 `scripts/windows/Migrate-GameRealismV2.ps1 -Migration AiBudget` 预检；通过后增加 `-Apply` 创建两张新增表。发布环境按 backend/sql/migrations.json 的顺序迁移。
4. 设置 `app.ai.system-user-id`、`app.ai.default-model`、`app.ai.budget-max-output-tokens`（默认 1024）。确认账号可用额度足以覆盖 Prepare 返回上限，再设置 `app.ai.budget-enabled=true`。
5. 只发一次受控文本调用，核对供应商调用数、网关 SUCCEEDED 状态、项目 SETTLED 和 CONSUME 流水。失败时关新调用开关，保留托管及对账数据。

严格预留金额可能高于实际费用。只有供应商上下文上限可核验时，不得用字符估算缩小输入预算。补充 10000 积分不保证足以覆盖整个上下文上限。

## 验证

专项覆盖并发预留、重复结算、输入不足零发送、响应丢失后状态恢复、未知用量不释放、临时退款到期日、过期差额及事务失败。服务端专项还覆盖真实 HTTP 发送拦截、发送次数硬限、输出上限、结果提交失败及过期/重启状态恢复。H2 测试不能替代目标 MySQL schema 与真实 DeepSeek 验收。
