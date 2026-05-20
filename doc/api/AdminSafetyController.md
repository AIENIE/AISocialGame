# AdminSafetyController

基础路径：`/api/admin/safety`

所有接口需要 `X-Admin-Token`。

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
