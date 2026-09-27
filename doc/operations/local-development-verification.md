# Windows 本地开发与验收

验收日期：2026-09-11～2026-09-12。范围为当前 Windows checkout；未提交或推送。

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

## 本轮结果

后端：119 项，0 failures、0 errors、2 skipped；前端：2 个文件、19 项通过。lint、类型检查、构建通过。

完整启动、localhost 健康检查、HTTPS 首页和基础 JSON API 实测通过；前后端 VS Code 断点实际命中。

Windows 下 Vitest worker 曾在启动阶段超时；固定 threads / 1 worker 后，从其他工作目录执行根入口的前端 L2 返回 0。POSIX 权限用例使用支持 POSIX 属性的 Jimfs 测试文件系统，生产权限校验保持严格。

六项目共享入口回归覆盖参数转发、失败退出码、跨工作目录、任务引用及 L3 调用清理；另有健康响应 mock 测试覆盖 string/UTF-8 byte[]、非 UP、非法 JSON 和非 200。日志与断点截图存于本机 %LOCALAPPDATA%/Aienie/verification/20260911-start；完整跨项目结论见父目录 local-development-verification.md。

## 边界

本轮分别执行构建/单测与真实运行阶段；不把这些记录写成每个项目最终版本的完整 L3 命令均已通过。付费 AI 请求、登录后长业务流程和可选外部集成不在本轮自动测试范围。当前 Node 24.13.0 与部分项目声明的 22.23.2 不同，虽已记录测试通过，复现时应遵循各项目声明版本。私有环境文件未写入仓库。
