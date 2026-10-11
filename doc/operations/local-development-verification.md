# Windows 本地开发与验收

本文维护 Windows 本地开发入口和验证级别，不作为执行结果。

## 统一入口

在仓库根目录打开 PowerShell 7 或 VS Code。也可从任意目录以绝对路径调用根 start.ps1。

```powershell
pwsh -NoProfile -File .\start.ps1
pwsh -NoProfile -File .\start.ps1 -Action Build
pwsh -NoProfile -File .\start.ps1 -Action Test -Level L2
pwsh -NoProfile -File .\start.ps1 -Action Start -EnableBackendDebug -NoBrowser
pwsh -NoProfile -File .\start.ps1 -Action Status
pwsh -NoProfile -File .\start.ps1 -Action Stop
```

默认 Action 为 Start：先完成必要构建，再启动并检查服务，成功后打开规范 HTTPS 入口。Start 不运行单元测试；-NoBrowser 用于自动化验收。选择参数为 -Component All|Backend|Frontend。原有私有环境、证书、管理员或 Python 路径参数继续由脚本接收。

- L1：构建和项目适用的静态检查。
- L2：L1 + 单元测试；默认测试级别。
- L3：L2 + 真实启动、localhost 健康检查、规范 HTTPS/基础 API 验收；finally 仅清理本轮新建的实例。已有实例保留。
- 已有健康实例复用；普通实例不能被隐式重启为调试模式。Start/Build/Test/Stop 使用项目锁避免并发修改同一运行状态。
- Stop 只处理当前 checkout 的身份匹配记录；不按端口直接杀进程。

## 端口与 VS Code

HTTP 前端 / 后端：11030 / 11031。Java JDWP：51031，仅监听 127.0.0.1。入口：https://localsocialgame.testhut.top/。

根 .vscode/tasks.json 提供 local: build/test/start/debug start/status/stop；launch.json 提供 Java attach、Edge 源码调试和组合调试。F5 使用根入口任务，组合调试只启动一次。Java Test Explorer 和 CodeLens 用于运行/调试单个后端测试；前端测试通过任务执行。自动 Java 构建关闭，避免与 Maven 的生成源码阶段争用，改代码后先执行 build。

## 验证边界

验证级别以当前任务授权为准；工程静态检查不证明运行时业务验收通过。付费调用、共享数据库变更及其他环境操作须按既有授权执行。遵循项目声明的 Node/Java 版本，私有配置与执行证据保存在仓库外。


本机运行目录默认从仓库相对位置定位同级 `aienie-runtime`；不同检出布局可显式设置 `AIENIE_RUNTIME_ROOT`。应用配置仍通过已有 `-EnvironmentFile` 参数覆盖；不要把个人主目录或固定工作区路径写回仓库。
