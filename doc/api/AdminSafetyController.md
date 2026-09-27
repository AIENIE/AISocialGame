# AdminSafetyController

基础路径：`/api/admin/safety`

所有接口需要 HttpOnly 管理员会话 cookie。

## GET /summary

返回安全运营摘要：

- `openHighRiskEvents`
- `blockedLast24h`
- `costAnomaliesLast24h`
- `activeControls`

## GET /events

分页查询安全事件。

查询参数：

- `status`
- `severity`
- `source`
- `roomId`
- `userId`
- `personaId`
- `modelKey`
- `page`
- `size`

## GET /events/{id}

查看单个安全事件详情。详情只返回内容摘要、替换内容和审计字段，不返回完整 Prompt 或隐藏信息。

## POST /events/{id}/ack

确认事件，状态变为 `ACKED`。

## POST /events/{id}/close

关闭事件。

请求体：

```json
{
  "reason": "admin_closed"
}
```

## GET /controls

查询当前仍有效的临时控制。

## POST /controls

创建临时控制。

```json
{
  "scope": "USER",
  "targetKey": "user-1",
  "action": "BLOCK",
  "reason": "危险聊天",
  "expiresAt": "2026-05-20T12:00:00"
}
```

`scope` 支持：`USER`、`ROOM`、`PERSONA`、`MODEL`、`GLOBAL`。

## DELETE /controls/{id}

停用临时控制。


## 当前控制契约（2026-09-22）

动作白名单：BLOCK、REDACT、RATE_LIMIT、ESCALATE、MUTE、PAUSE_ROOM、FORCE_OBSERVE、DISABLE_AI。PAUSE_ROOM/FORCE_OBSERVE只接受ROOM或GLOBAL；各范围均需非空targetKey（GLOBAL可用约定标识）；提供的expiresAt必须在未来。非法范围/动作/期限返回400，不静默创建无效控制。

- MUTE 限制公开表达；不拦截私密夜间动作。
- PAUSE_ROOM 冻结自动推进和倒计时；恢复保留暂停前剩余时间。
- FORCE_OBSERVE 停AI调用/自动托管、保留真人动作；需要AI主持时等待恢复。
- DISABLE_AI及严格阻止控制在RPC前生效，在途结果提交前复查。最严格的适用限制优先。
- 创建/撤销保存操作人及CONTROL_CHANGE审计；ack/close也保存操作人。到期后不再命中。

调用频率与用量统计来自本地 ai_call_usage，不读取 m4_test_* 文本作为生产触发器。被拒请求的审计独立于业务事务；没有新增公开诊断端点。详细阈值、数据库升级和限制见 [收尾报告](../test/m1-m5-closure-20260922.md)。
