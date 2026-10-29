# 房间生命周期与本地迁移

房间使用不可复用的六位数字号码（100000–999999）进行精确搜索，原 UUID 路由继续有效。创建时选择公开或私密，默认公开；公开房间进入大厅分页列表，私密房间仅通过房间号或邀请链接发现，加入仍需密码。搜索摘要不包含座位身份、密码和游戏配置。

每轮 WAITING 最多三小时，首次从创建时间计时，后续从结算进入等待时重新计时。加入、聊天、添加 AI 不续期。PLAYING 不参与过期；EXPIRED 永久不能重新激活，历史对局和回放保留。请求入口与每分钟扫描共用房间行锁，达到期限返回 HTTP 410 / ROOM_EXPIRED，错误响应不撤销过期持久化。在线成员收到私有 ROOM_EXPIRED 事件；页面同时在截止时间、聚焦及轮询时复核。

## 接口

- `GET /api/rooms/search?roomCode=123456`：登录后跨玩法精确搜索；非法号码 400，不存在 404，失效 410。
- `GET /api/games/{gameId}/rooms/{roomId}/entry`：最小加入引导摘要；验证玩法一致，不提前读取私密业务数据。
- 大厅分页和总数只统计有效公开房间，默认 WAITING 和 PLAYING；快速匹配明确请求 WAITING。
- 创建、加入继续使用 `isPrivate`、`password`；进行中的私密房间不开放密码观战或中途入座。
- 房间详情新增 `roomCode`、`waitingSince`、`expiresAt`；过期后运行组件卸载，在途请求取消，当前房间缓存清除，订阅关闭。

## 数据结构

当前新库使用 `backend/sql/schema.sql`。历史增量 `20261008_room_lifecycle.sql`（序号 11）保持冻结，用于记录已发布的结构演进；一次性本地回填适配入口已经退役。需要升级其他现存数据库时，由操作者按其实际结构选择尚未执行的增量，并单独处理旧数据；不重放已执行的历史适配。

## 验证入口

```powershell
.\scripts\windows\Test-Local.ps1 -Component All -Level L2
.\scripts\windows\Test-ClosureMySql.ps1
```

MySQL 验证使用隔离的 Windows 原生实例，验证当前建库快照、现行数据清理、金额记录保留、结构约束及幂等。浏览器回归覆盖 `frontend/tests/room-discovery.spec.ts`、`room-experience.spec.ts`、`access-boundaries.spec.ts`。
