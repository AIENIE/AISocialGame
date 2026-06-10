# Codex 代码库阅读入口

本仓库是 Aienie 的 AISocialGame 业务项目。Codex 读取代码时按以下顺序建立上下文：

1. 先读根目录 `AGENTS.md`，确认项目边界、域名、端口和部署脚本约束。
2. 再读 `doc/README.md`，按任务进入里程碑、结构、模块、API 或测试文档。
3. 涉及公共服务接口时，读取 `/home/duwei/aienie-services/aienie-doc/interfaces/{user-service,ai-service,pay-service}/` 下对应服务契约。
4. 先用 CodeGraph 查模块结构、调用链和影响面候选，再用 Serena 查 definition、references、symbol overview 和 rename。
5. 图谱或 LSP 结论只作为定位依据；修改前必须打开真实源码和相关文档确认。

常用入口：

- `backend/src/main/java/com/aisocialgame/`：Spring Boot 后端实现。
- `backend/src/main/proto/`：本项目复制使用的 gRPC 契约。
- `frontend/src/`：React + TypeScript 前端源码。
- `doc/structure.md`：项目结构说明。
- `doc/modules/`：GameEngine、海龟汤、安全治理、性能稳定性等模块说明。
- `doc/api/`：API 文档。
- `doc/milestones/`：阶段开发记录。
