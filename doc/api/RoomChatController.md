# RoomChatController 接口说明（WebSocket）

## 简介
- 职责：处理房间聊天消息（文本、表情、快捷短语）并广播给同房间订阅者。
- 鉴权要求：必须通过 STOMP CONNECT 建立 `Principal`（`Authorization: Bearer <token>`）。
- 基础路径：STOMP `@MessageMapping`，应用前缀 `/app`。

## 接口列表

| 类型 | 路径 | 用途 |
|---|---|---|
| STOMP SEND | `/app/room/{roomId}/chat` | 发送聊天消息 |
| STOMP SUBSCRIBE | `/topic/room/{roomId}/chat` | 订阅房间聊天广播 |

## 发送消息详情

### SEND `/app/room/{roomId}/chat`

**用途**：发送聊天消息给当前房间。

**请求体**

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| type | String | 否 | `TEXT`/`EMOJI`/`QUICK_PHRASE`，默认 `TEXT` | `TEXT` |
| content | String | 是 | 聊天内容 | `我认为 3 号可疑` |

**处理规则**
- 仅允许房间内已入座玩家发送。
- 频率限制：同一玩家最小发送间隔 3 秒。
- 文本消息在 `NIGHT` 阶段被拦截；`EMOJI`/`QUICK_PHRASE` 允许发送。
- 长度限制：`TEXT` 最大 200，其他类型最大 40。

**广播返回（ChatMessage）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | String | 消息 ID |
| roomId | String | 房间 ID |
| senderId | String | 发送者 playerId |
| senderName | String | 发送者昵称 |
| senderAvatar | String | 发送者头像 |
| type | String | 消息类型 |
| content | String | 消息内容 |
| timestamp | Long | 毫秒时间戳 |

## 订阅详情

### SUBSCRIBE `/topic/room/{roomId}/chat`

**用途**：接收本房间广播聊天消息。

**注意**
- 服务端对非法消息/超频消息会静默丢弃，不返回错误帧。
