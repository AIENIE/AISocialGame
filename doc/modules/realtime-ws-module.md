# 实时通信模块（WebSocket / STOMP）

## 模块作用
- 提供房间状态、座位变化、私有事件、聊天消息的低延迟推送通道。
- 统一管理 WS 鉴权、连接状态与消息限流。

## 功能点清单
| 功能点 | 作用 | 实现位置 |
|---|---|---|
| STOMP Broker 配置 | 注册 `/ws` 端点和 `/topic`、`/queue`、`/user` 目的地 | `backend/src/main/java/com/aisocialgame/config/WebSocketConfig.java` |
| WS 鉴权拦截 | 在 CONNECT 帧解析 `Authorization` 并绑定登录用户 Principal | `backend/src/main/java/com/aisocialgame/websocket/WebSocketAuthChannelInterceptor.java` |
| 连接状态跟踪 | 跟踪会话连接、断开、活跃打点，提供在线判定 | `backend/src/main/java/com/aisocialgame/websocket/PlayerConnectionService.java`、`backend/src/main/java/com/aisocialgame/websocket/WebSocketEventListener.java` |
| 推送服务 | 对外统一推送 `state/private/seat/chat` 四类消息 | `backend/src/main/java/com/aisocialgame/websocket/GamePushService.java` |
| 房间聊天入口 | 处理 `TEXT/EMOJI/QUICK_PHRASE`，做限流与阶段限制后广播 | `backend/src/main/java/com/aisocialgame/controller/RoomChatController.java`、`backend/src/main/java/com/aisocialgame/websocket/ChatRateLimiter.java` |
| 前端 WS 客户端 | 原生 WebSocket + STOMP 帧，支持自动重连和聊天发送 | `frontend/src/hooks/useGameSocket.ts` |

## 关键流程
1. 前端连接 `/ws` 并发送 CONNECT 帧（携带 `Authorization: Bearer <token>`）。
2. 后端校验身份，建立 Principal，并记录会话到 `PlayerConnectionService`。
3. 前端订阅：
  - `/topic/room/{roomId}/state`
  - `/topic/room/{roomId}/seat`
  - `/topic/room/{roomId}/chat`
  - `/user/queue/private`
4. 游戏服务或房间服务触发动作后，由 `GamePushService` 推送对应事件。

## 稳定性策略

- `PlayerConnectionService` 定期清理无 session 且超过保留窗口的玩家连接状态。
- `ChatRateLimiter` 定期清理长时间未发言玩家，避免玩家 ID 积累导致内存增长。
- 清理周期通过 `app.websocket.connection-cleanup-interval-ms` 和 `app.websocket.rate-limit-cleanup-interval-ms` 配置。

## 相关文件
- `backend/src/main/java/com/aisocialgame/dto/ws/GameStateEvent.java`
- `backend/src/main/java/com/aisocialgame/dto/ws/PrivateEvent.java`
- `backend/src/main/java/com/aisocialgame/dto/ws/SeatEvent.java`
- `backend/src/main/java/com/aisocialgame/dto/ws/ChatMessage.java`
