# 大厅/房间模块说明

## 目标
支撑“创建房间 → 等待/入座 → 开局”的通用流程，并通过玩法组件注册表对不同玩法（狼人杀、谁是卧底）提供定制 UI。

## 后端职责
- 提供游戏配置 schema（`/api/games`）驱动前端动态表单。
- 房间生命周期：
  - 创建：保存配置、人数上限、公开/私密、沟通模式。
  - 入座：人类或游客占位，房主自动入座。
  - 添加 AI：基于 `/api/personas` 预设补位。
- 房间实时事件：
  - 入座/补位后广播 `SeatEvent` 到 `/topic/room/{roomId}/seat`。
  - 房间聊天由 STOMP 入口 `/app/room/{roomId}/chat` 接收，并广播到 `/topic/room/{roomId}/chat`。
- 房间、座位信息持久化在 MySQL（座位 JSON 存储），响应中返回 `selfPlayerId` 便于游客重连不重复占位。

## 前端职责
- **动态表单**：`CreateRoom.tsx` 根据 schema 渲染配置项。
- **房间列表**：`RoomList.tsx` 读取 `/rooms` 并跳转。
- **大厅页**：
  - 通用大厅 `Lobby.tsx`：加载房间详情、自动入座、添加 AI（聊天室占位已移除，日志统一在玩法页呈现）。
  - 玩法组件注册：`pages/games/registry.tsx` 按 `gameId` lazy 加载玩法页。
  - 玩法大厅 `games/UndercoverRoom.tsx`、`games/WerewolfRoom.tsx`：基于 `useGameSocket` 接收实时推送，并通过 `useGameEngine` 的统一 `/action` 提交发言、投票和夜晚行动。
- **鉴权/游客**：`useAuth` 负责 token 缓存与游客名生成。

## 数据流
1. 首页获取游戏列表。
2. 进入游戏房间列表，前端拉取 `/rooms`。
3. 创建房间 → 成功后跳转 `/room/:gameId/:roomId`，自动调用 `/join` 并缓存 `selfPlayerId`。
4. 在大厅添加 AI → `/ai`，后端通过 WS 推送座位变化。
5. 玩法页通过 WS `state/seat/private/chat` 多路订阅驱动界面刷新，必要时再调用 `/state` 校准数据。
6. 玩家动作优先走 `/api/games/{gameId}/rooms/{roomId}/action`，旧动作接口继续兼容现有客户端。
7. 聊天文本在夜晚阶段受限，表情和快捷短语可发送；发送频率受后端限流控制。

## 已知限制
- 当前环境若后端依赖未就绪，前端会显示“连接中断，正在自动重连”，聊天发送会提示失败。
