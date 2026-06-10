# 模块 01：WebSocket 实时通信基础设施

> 优先级：P0 | 阶段：第一阶段 | 依赖：无 | 被依赖：几乎所有模块

## 1. 背景与目标

当前系统使用 React Query 以 2 秒间隔轮询 `/api/games/{gameId}/rooms/{roomId}/state` 获取游戏状态。这导致：

- 阶段切换延迟最高 2 秒，投票/发言体验不流畅
- 服务端承受大量无效请求（状态未变化时的空轮询）
- 无法实现即时通知（轮到你操作、有人发言等）

目标：引入 WebSocket（STOMP 协议），实现游戏状态变更的服务端主动推送，将延迟降至 100ms 以内。

## 2. 技术选型

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| Spring WebSocket + STOMP | Spring 原生支持，与现有架构一致，支持 topic 订阅 | 需要额外配置 | **采用** |
| Netty 原生 WebSocket | 性能极高 | 与 Spring 生态割裂，开发成本高 | 不采用 |
| Socket.IO (via netty-socketio) | 自动降级、房间抽象 | 引入额外依赖，Java 生态支持一般 | 不采用 |

前端选用 `@stomp/stompjs`，轻量且与 STOMP 协议完全匹配。

## 3. 架构设计

```
┌─────────────┐     WebSocket (STOMP)      ┌──────────────────┐
│  React App  │ ◄──────────────────────────► │  Spring Boot     │
│             │   /ws  (SockJS fallback)    │                  │
│  stompjs    │                             │  WebSocketConfig │
│  client     │   Subscribe:                │  GamePushService │
│             │   /topic/room/{roomId}      │                  │
│             │   /user/queue/private       │                  │
└─────────────┘                             └──────────────────┘
```

### 3.1 消息 Topic 设计

| Topic | 方向 | 用途 | 消息体 |
|-------|------|------|--------|
| `/topic/room/{roomId}/state` | Server → Client | 游戏状态变更推送 | `GameStateEvent` |
| `/topic/room/{roomId}/chat` | Server → Client | 房间聊天消息 | `ChatMessage` |
| `/topic/room/{roomId}/seat` | Server → Client | 座位变动（加入/离开） | `SeatEvent` |
| `/user/queue/private` | Server → 指定用户 | 私密信息（你的角色、预言家查验结果） | `PrivateEvent` |
| `/app/room/{roomId}/chat` | Client → Server | 发送聊天消息 | `ChatMessage` |

### 3.2 消息体定义

```java
// 游戏状态变更事件
public record GameStateEvent(
    String type,        // PHASE_CHANGE | SPEAK | VOTE | PLAYER_JOIN | PLAYER_LEAVE | SETTLEMENT
    String phase,       // 当前阶段
    int round,          // 当前轮次
    Integer currentSeat,
    Object payload      // 具体变更数据，按 type 不同结构不同
) {}

// 座位变动事件
public record SeatEvent(
    String type,        // JOIN | LEAVE | AI_ADDED
    RoomSeat seat
) {}

// 私密事件
public record PrivateEvent(
    String type,        // ROLE_ASSIGNED | SEER_RESULT | PENDING_ACTION
    Object payload
) {}
```

## 4. 后端实现

### 4.1 WebSocket 配置

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 服务端推送前缀
        registry.enableSimpleBroker("/topic", "/queue");
        // 客户端发送前缀
        registry.setApplicationDestinationPrefixes("/app");
        // 用户私有队列前缀
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // SockJS 降级兜底
    }
}
```

### 4.2 连接认证

```java
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final AuthService authService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("Authorization");
            String playerId = accessor.getFirstNativeHeader("X-Player-Id");
            // 验证 token 或 playerId，设置 Principal
            if (token != null) {
                User user = authService.validateToken(token);
                accessor.setUser(new StompPrincipal(user.getId()));
            } else if (playerId != null) {
                accessor.setUser(new StompPrincipal(playerId));
            }
        }
        return message;
    }
}
```

### 4.3 推送服务

```java
@Service
public class GamePushService {

    private final SimpMessagingTemplate messagingTemplate;

    // 广播游戏状态变更到房间
    public void pushStateChange(String roomId, GameStateEvent event) {
        messagingTemplate.convertAndSend(
            "/topic/room/" + roomId + "/state", event);
    }

    // 推送私密信息给指定玩家
    public void pushPrivate(String playerId, PrivateEvent event) {
        messagingTemplate.convertAndSendToUser(
            playerId, "/queue/private", event);
    }

    // 推送座位变动
    public void pushSeatChange(String roomId, SeatEvent event) {
        messagingTemplate.convertAndSend(
            "/topic/room/" + roomId + "/seat", event);
    }
}
```

### 4.4 推送触发点

在现有 `GamePlayService` 的关键方法中注入 `GamePushService`，在状态变更后触发推送：

| 方法 | 推送内容 |
|------|----------|
| `start()` | `PHASE_CHANGE` 事件 + 各玩家 `ROLE_ASSIGNED` 私密事件 |
| `speak()` | `SPEAK` 事件（含发言内容） |
| `vote()` | `VOTE` 事件（含投票统计） |
| `nightAction()` | `PHASE_CHANGE`（夜晚结算后） + 预言家 `SEER_RESULT` |
| `finishGame()` | `SETTLEMENT` 事件（含完整揭示数据） |
| `RoomService.join()` | `SeatEvent.JOIN` |
| `RoomService.addAi()` | `SeatEvent.AI_ADDED` |

## 5. 前端实现

### 5.1 useGameSocket Hook

```typescript
// hooks/useGameSocket.ts
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

interface UseGameSocketOptions {
  roomId: string;
  playerId: string | null;
  token: string | null;
  onStateChange: (event: GameStateEvent) => void;
  onPrivate: (event: PrivateEvent) => void;
  onSeatChange: (event: SeatEvent) => void;
}

export function useGameSocket(options: UseGameSocketOptions) {
  const clientRef = useRef<Client | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: {
        Authorization: options.token || '',
        'X-Player-Id': options.playerId || '',
      },
      reconnectDelay: 3000,        // 断线 3 秒后自动重连
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        setConnected(true);
        // 订阅房间状态
        client.subscribe(
          `/topic/room/${options.roomId}/state`,
          (msg) => options.onStateChange(JSON.parse(msg.body))
        );
        // 订阅私密消息
        client.subscribe(
          '/user/queue/private',
          (msg) => options.onPrivate(JSON.parse(msg.body))
        );
        // 订阅座位变动
        client.subscribe(
          `/topic/room/${options.roomId}/seat`,
          (msg) => options.onSeatChange(JSON.parse(msg.body))
        );
      },
      onDisconnect: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, [options.roomId, options.playerId]);

  return { connected, client: clientRef };
}
```

### 5.2 与 React Query 的协作策略

- **初始加载**：进入房间时仍通过 REST API 获取完整游戏状态（`useQuery` 单次请求）
- **增量更新**：WebSocket 推送的事件用于更新 React Query 缓存（`queryClient.setQueryData`）
- **兜底同步**：WebSocket 重连后，主动调用一次 REST API 全量同步，防止丢失消息
- **移除轮询**：`refetchInterval` 设为 `false`，不再定时轮询

```typescript
// 在游戏房间组件中
const queryClient = useQueryClient();

useGameSocket({
  roomId, playerId, token,
  onStateChange: (event) => {
    // 用推送数据更新缓存
    queryClient.setQueryData(['game-state', roomId], (old) => {
      return applyStateEvent(old, event);
    });
  },
  // ...
});
```

## 6. 连接生命周期管理

```
用户进入房间
  │
  ├─► WebSocket CONNECT（携带 token/playerId）
  │     │
  │     ├─► 认证成功 → SUBSCRIBE 房间 topics
  │     │
  │     └─► 认证失败 → 降级为 REST 轮询
  │
  ├─► 网络断开
  │     │
  │     ├─► stompjs 自动重连（3s 间隔，指数退避最大 30s）
  │     │
  │     ├─► 重连成功 → 全量同步一次 REST API
  │     │
  │     └─► 重连超过 60s → 显示"连接已断开"提示，提供手动重连按钮
  │
  └─► 用户离开房间
        │
        └─► WebSocket DISCONNECT + UNSUBSCRIBE
```

## 7. Nginx 代理配置

```nginx
# 在现有 nginx.conf 中添加 WebSocket 代理
location /ws {
    proxy_pass http://backend:11031/ws;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 86400s;  # 24h，防止空闲断开
    proxy_send_timeout 86400s;
}
```

## 8. 性能与容量

| 指标 | 预估值 | 说明 |
|------|--------|------|
| 单房间连接数 | ≤ 12 | 最大玩家数 + 观战者 |
| 单服务器并发连接 | ~5000 | Spring WebSocket 默认线程池足够 |
| 消息频率 | ~2 msg/s/room | 发言+投票+阶段切换 |
| 消息大小 | < 2KB | JSON 序列化的事件体 |

## 9. 依赖变更

### 后端 (pom.xml)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 前端 (package.json)
```json
{
  "@stomp/stompjs": "^7.0.0",
  "sockjs-client": "^1.6.1"
}
```

## 10. 数据库变更

无。WebSocket 是纯通信层，不涉及持久化变更。

## 11. 兼容性与降级

- 浏览器不支持 WebSocket 时，SockJS 自动降级为 XHR-streaming / long-polling
- 后端推送失败不影响游戏逻辑（游戏状态仍由 REST API 驱动，WebSocket 只是通知层）
- 可通过配置开关 `app.websocket.enabled=true/false` 控制是否启用

## 12. 测试要点

- [ ] WebSocket 连接建立与认证
- [ ] 房间订阅与消息接收
- [ ] 断线自动重连 + 状态同步
- [ ] SockJS 降级场景
- [ ] 多客户端同时订阅同一房间
- [ ] Nginx 代理下的 WebSocket 连通性
- [ ] 高并发连接压测（JMeter WebSocket 插件）
