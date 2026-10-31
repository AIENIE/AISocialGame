# 日志与可观测性模块

## 后端日志

Spring Boot 的日志配置集中在 `backend/src/main/resources/application.yml`：

- 默认写入 `${APP_LOG_DIR:./logs}/aisocialgame.log`。
- 文件日志使用 Spring Boot ECS JSON；异常堆栈作为单行 JSON 字符串输出，MDC `requestId` 作为结构化字段输出。console 保持现有文本展示。
- 单文件默认上限 `10MB`，历史默认保留 `14` 天，总容量默认上限 `1GB`。
- 可通过 `LOGGING_MAX_FILE_SIZE`、`LOGGING_MAX_HISTORY`、`LOGGING_TOTAL_SIZE_CAP` 和 `LOGGING_CLEAN_HISTORY_ON_START` 调整轮转策略。
- 根日志和应用日志默认均为 `INFO`，分别由 `LOGGING_LEVEL_ROOT`、`LOGGING_LEVEL_APP` 控制。生产环境不应开启全局 `DEBUG` 或 `TRACE`。
- 发版运行时的容器 stdout/stderr 轮转由部署侧 Docker daemon 策略管理；应用文件日志写入运行时 Compose 挂载的 `./logs` 卷，records 写入 `./records` 卷。

部署时应让日志目录只对运行账户及运维组可写，并使用受限的进程 `umask`；应用配置只负责轮转与容量，不替代宿主机权限控制。

## 请求关联

`RequestIdFilter` 接收 `X-Request-Id`，仅保留 1 至 64 位的字母、数字、点、下划线、冒号或连字符；缺失或不合法时生成新的 UUID。最终 ID 会写入响应头和 MDC，console 日志级别字段与 ECS 文件日志字段输出 `requestId`，并在 `finally` 中清理，防止线程复用时串号。`aiStreamTaskExecutor` 会把调用方 MDC 传播到异步任务，并在任务结束后恢复 worker 原上下文。

## 安全与关键路径

- 管理员 challenge、数据库 session、恢复码、操作 proof 与认证审计由 `AdminAuthStore` 持久化；Redis 只承担认证限流。登录、验证、重绑和 logout 写入固定审计事件，logout 会显式撤销数据库 session；任何日志与审计均禁止记录密码、TOTP、会话 cookie、challenge、proof 原文或 Redis key。
- ban/unban、兑换码创建和 persona memory reset 使用真实 `@CurrentAdmin` username 作为受限 actor，并统一记录配置化的 numeric `operatorUserId`、action、非敏感 targetId、结果和 errorType；禁止记录 reason、兑换码、token 或原始内容。失败日志不附带可能包含业务值的异常消息，异常仍会原样向上抛出。
- UserService 的 ban/unban gRPC 合同要求 numeric `operatorUserId`。AISocialGame 通过 `app.admin.operator-user-id` / `APP_ADMIN_OPERATOR_USER_ID` 统一提供，默认值为 `1` 且必须为正数；该值代表跨服务审计身份，不将 username 猜测或转换为 UserService 用户 ID。若未来启用多管理员，应由统一身份目录分配稳定 numeric ID，而不是继续共享默认值。
- AI 决策调用异常、行动失败和 trace/persona memory 持久化异常记录 game、room、player、persona、action 等实体标识；诊断 throwable 只保留原异常类型标识和 stack trace，不保留可能含远端响应的 message、cause 或 suppressed，且禁止记录 prompt、模型原始输出或完整业务上下文。
- 全局未处理异常记录固定事件与 `errorType`，requestId 直接来自 MDC/ECS 字段；诊断 throwable 同样只保留 stack trace，不重复打印请求 ID，也不记录异常 message/cause/suppressed。
- 业务异常字段应优先使用稳定 ID、枚举和计数，不应把认证头、Cookie、密码、兑换码或第三方原始响应写入日志。

## 前端错误捕获

应用入口安装幂等的 `error`、`unhandledrejection` 监听器，并用 `SafeErrorBoundary` 捕获 React 渲染错误。浏览器控制台和 `aienie:client-error` 事件不传递原始 `Error`、拒绝对象、路由或堆栈，只接收定长并对常见 token、密钥参数、邮箱和长 opaque ID 模式脱敏的摘要；调用方仍不得主动把原始用户输入放入错误消息。

当前前端只提供本地安全摘要和扩展事件，不代表错误已远程上报或持久化。若后续接入采集平台，应继续传输同一摘要结构，并在服务端执行二次脱敏、采样和容量控制。

## 聚焦测试

- `RequestIdFilterTest` 覆盖合法 ID 透传、不合法 ID 重生成、异常请求后的 MDC 清理。
- `AdminAuthServiceTest` 覆盖本地密码模式签发数据库会话、TOTP 模式密码后 challenge，以及错误密码不创建会话；认证敏感值不写日志的约束由实现审计与专项安全检查共同验证。
- `AdminOpsServiceLoggingTest` 覆盖 ban/unban 使用统一 numeric operator ID，以及兑换码创建成功/失败日志只使用数据库 ID 或固定占位符，且不记录 bearer code 或异常消息。
- `SafeLogThrowableTest` 覆盖安全诊断异常只保留类型标识和 stack trace。
- `MdcTaskDecoratorTest` 覆盖异步任务的 MDC 传播，以及正常和异常结束时的 worker 上下文恢复。
