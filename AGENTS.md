- 项目名: AISocialGame
- 项目归属：aienie
- 项目类型：业务项目（projects）
- 前端技术栈: React 18 + TypeScript + Vite + React Router + TanStack Query + Tailwind CSS + shadcn/ui
- 后端技术栈: Java Spring boot
- 本地环境域名: localsocialgame.testhut.top
- 预发布环境域名: socialgame.testhut.top
- 生产环境目标域名（切换完成前不得作为当前入口）: socialgame.seekerhut.com
- 前端对外端口: 11030
- 后端对外端口: 11031

在linux环境下，执行sudo的密码请从SUDO_PASSWORD环境变量获取。
- 发版入口: 发版中心统一使用 scripts/ci/build-release.sh（Linux Docker 部署，运行时配置由 config-center 提供，不使用仓库本地配置）。
- 本段描述仓库提供的技术入口，实际目标环境和操作范围按当前任务及适用运行规范确定，不因文档列出脚本而自动执行。
- 本地 Windows 一键调试入口: scripts/windows/Start-Local.ps1（后端+前端一次拉起为受管后台进程，等待健康检查后自动在默认浏览器打开本地域名主页，重复运行安全，-NoBrowser 可跳过浏览器）与 Stop-Local.ps1；底层保留 Start-Backend.ps1 与 Start-Frontend.ps1 前台调试（不使用 Docker）。
