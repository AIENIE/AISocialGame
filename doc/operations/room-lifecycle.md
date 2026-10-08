# 房间生命周期与本地迁移

房间使用不可复用的六位数字号码（100000–999999）进行精确搜索，原 UUID 路由继续有效。创建时选择公开或私密，默认公开；公开房间进入大厅分页列表，私密房间仅通过房间号或邀请链接发现，加入仍需密码。搜索摘要不包含座位身份、密码和游戏配置。

每轮 WAITING 最多三小时，首次从创建时间计时，后续从结算进入等待时重新计时。加入、聊天、添加 AI 不续期。PLAYING 不参与过期；EXPIRED 永久不能重新激活，历史对局和回放保留。请求入口与每分钟扫描共用房间行锁，达到期限返回 HTTP 410 / ROOM_EXPIRED，错误响应不撤销过期持久化。在线成员收到私有 ROOM_EXPIRED 事件；页面同时在截止时间、聚焦及轮询时复核。

## 接口

- `GET /api/rooms/search?roomCode=123456`：登录后跨玩法精确搜索；非法号码 400，不存在 404，失效 410。
- `GET /api/games/{gameId}/rooms/{roomId}/entry`：最小加入引导摘要；验证玩法一致，不提前读取私密业务数据。
- 大厅分页和总数只统计有效公开房间，默认 WAITING 和 PLAYING；快速匹配明确请求 WAITING。
- 创建、加入继续使用 `isPrivate`、`password`；进行中的私密房间不开放密码观战或中途入座。
- 房间详情新增 `roomCode`、`waitingSince`、`expiresAt`；过期后运行组件卸载，在途请求取消，当前房间缓存清除，订阅关闭。

## Windows 本地执行

迁移文件为 `backend/sql/20261008_room_lifecycle.sql`，清单序号 11。操作仅限矩阵中的 Windows 本地 `localmysql.testhut.top` / `aisocialgame`。脚本解析规范矩阵并核验环境，凭据通过进程环境传递，不写日志。先停止旧后端，避免旧版本在新增非空号码字段后继续写房间。

```powershell
.\scripts\windows\Migrate-LocalRooms.ps1 -Mode dry-run
.\scripts\windows\Stop-Local.ps1 -Component Backend
.\scripts\windows\Migrate-LocalRooms.ps1 -Mode apply
.\scripts\windows\Start-Local.ps1 -Component Backend -NoBrowser -StartupTimeoutSeconds 600
.\scripts\windows\Get-LocalStatus.ps1
```

dry-run 不执行 DDL 或数据修改。apply 按原号码保留、只给缺失号码分配未用号码，支持失败后重复执行；MySQL DDL 隐式提交，整个迁移不是单一原子事务。不会自动备份，不改变用户余额，不删除历史记录。兼容 Hibernate 曾创建的两值 MySQL ENUM 状态列，将其转为建库契约的 VARCHAR(32)。候选号码限定在足够容纳当前所有房间的最小连续区间，避免枚举全部 90 万个号码造成超时。等待起点优先采用最近归档结束时间，其次已结算状态时间；从未开局使用创建时间。无法确定起点的记录失效并报告数量和房间 ID。

历史 DATETIME 使用应用本地时钟，两个规范执行器显式传入 JVM 本地时间供迁移判断期限。不要在其他时区直接复制 SQL 执行。既有发布文件 `20260912_game_realism_v2.sql` 的已封存版本末尾有意外字面量 `\n`；迁移执行器仅对精确文件名及 SHA-256 `c12eb713005b82b7673ec154851c4cfadd92d30d8886ddc111a0593233c4f6cc` 在执行时移除末尾两字节，保持历史源文件与账本校验值不变。

## 验证入口

```powershell
.\scripts\windows\Test-Local.ps1 -Component All -Level L2
.\scripts\windows\Test-ClosureMySql.ps1
```

MySQL 验证使用隔离的 Windows 原生实例，验证建库、历史升级、部分 DDL 重试、结构约束、回填及幂等。浏览器回归覆盖 `frontend/tests/room-discovery.spec.ts`、`room-experience.spec.ts`、`access-boundaries.spec.ts`。
