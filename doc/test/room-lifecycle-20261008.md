# 房间生命周期验收（2026-10-08）

范围为 Windows 本地 AISocialGame，按已确认方案实现每轮等待三小时、公开/私密房间、六位房间号与精确搜索；未部署预发布或生产。验收级别为 L4，并运行相关桌面/手机 E2E 回归。

## 代码与接口

- 每轮等待起点持久化，开局与过期竞争同一房间行锁；达到期限的读取和写入均拒绝，410 后过期标记保留，重复扫描幂等。
- 初次等待、重新等待、进行中免过期、活动不续期、号码冲突重试、分页前过滤和最小搜索摘要均有后端测试。
- 私密房间通过摘要和加入引导后才挂载业务查询及订阅，错误密码可以重试或取消；新加入等待用户不能读取上一局私密信息。
- 页面接收过期通知或 410 后卸载业务组件，取消在途请求，清除该房间缓存并关闭订阅；截止时间和重新聚焦复核服务端状态。
- 简中、繁中、英文文案同步；保留认证守卫、用户查询隔离、旧 UUID 链接及回放。

## 自动化验证

- `Test-Local.ps1 -Component Backend -Level L2`：最终编译及全量测试通过。最终全量为 624 项，40 项依赖特定环境而跳过，失败和错误均为 0；`NativeWebSocketIntegrationTest` 共 4 项通过，验证通知、聊天拒绝及新订阅拒绝。
- `Test-Local.ps1 -Component Frontend -Level L2`：22 个文件、116 项单元测试通过。类型检查和构建通过，lint 无错误或新增警告（94 项既存警告）。最后文案及按钮换行调整后再次通过类型检查、lint 和构建。
- Playwright `room-discovery.spec.ts`、`room-experience.spec.ts`、`access-boundaries.spec.ts`：15 项通过，覆盖 1440px/390px、密码失败重试、取消不加载私密业务、失效链接、登录守卫、三玩法准备/对局/结算。
- `Test-ClosureMySql.ps1`：MySQL 8.4.11 原生隔离实例整套通过，覆盖完整迁移链、旧 ENUM、精确时间回填、号码保留与重试、部分 DDL 恢复、业务并发、结算幂等及 1000 条归档分页。最终 `result.json` 为 `passed: true` / `listenerRemaining: false`，临时实例已清理。证据目录为 `D:/project/aienie/aienie-runtime/evidence/product-artifacts/aisocialgame/mysql-629409e1e578404da2ccdd68fdefc9d7`。
- 日志位于忽略目录 `backend/target/room-final-backend.log`、`room-final-frontend.log`、`room-final-static.log`、`room-final-browser.log`、`room-delivery-backend-tests.log`、`room-delivery-frontend-static.log`、`room-native-websocket.log`。

## 本地迁移与真实页面

矩阵适配器核验 `localmysql.testhut.top` / `aisocialgame`，dry-run 无修改。停止旧后端后执行 apply，重复执行结果一致：迁移前 WAITING 60 / PLAYING 8；迁移后 EXPIRED 60 / PLAYING 8。其中 8 项无法确定等待起点，已按方案失效并报告房间 ID。未调整余额或删除历史记录。

本地历史表使用旧两值 MySQL ENUM，迁移兼容转换为 VARCHAR(32)。首次执行号码候选枚举触发 30 秒读取超时，已改为按房间总量限定足够分配的最小范围；重试及重复执行成功。MySQL DDL 隐式提交，执行器明确支持部分完成后重试，不宣称整次迁移原子完成。

通过 agent-browser / Edge 验证真实 SSO 登录后的创建、公开列表、私密搜索、跨玩法跳转、已加入返回、复制及旧失效链接。创建了公开房间 889946、私密房间 911255；验收未发起新对局，钱包读取仍为 1520。两房按正常业务规则等待三小时后自动失效。

最终构建完成后通过标准 Start-Local 重启后端，11030/11031 健康检查通过；本地域名为 `https://localsocialgame.testhut.top`。验收新浏览器会话未出现 JavaScript 异常。截图保存在当前 Codex 可视化目录的 `rooms/desktop-public-list.png`、`mobile-private-search.png`、`mobile-room.png`、`desktop-expired.png`，已人工查看手机布局。

保留开始工作时已有的 9 个无关修改，不纳入本次提交。操作方式及已封存历史 SQL 兼容说明见 `doc/operations/room-lifecycle.md`。
