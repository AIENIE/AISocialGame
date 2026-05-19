# AISocialGame v1.0 代码易读性与可维护性整改记录

> 整改日期：2026-05-19

## 整改摘要

本轮按 `maintainability-audit.md` 处理可读性与维护性问题，重点收敛控制器鉴权、前端房间页重复、前端 API 错误类型、账务账本横切逻辑、DTO 校验和历史文档事实。

## 已处理问题

| 原问题 | 处理方式 | 关键位置 |
|---|---|---|
| 房间页重复 | 抽出共享房间运行时 hook、房间框架、玩家网格、日志面板和 AI 补位控件 | `frontend/src/pages/games/shared/*` |
| 前端错误类型松散 | 新增 `getApiErrorMessage(error: unknown, fallback)`，房间页不再直接使用 `error: any` / `as any` | `frontend/src/services/apiError.ts` |
| 控制器重复鉴权 | 新增 `@CurrentUser`、`@CurrentAdmin` 参数解析器，业务控制器不再手写 token 认证 | `backend/src/main/java/com/aisocialgame/web/*` |
| DTO 校验不均衡 | 补充房间、发言、统一 action、夜晚行动、AI OCR 等请求长度、枚举和 URL 校验 | `backend/src/main/java/com/aisocialgame/dto/*` |
| 账务服务账本职责混杂 | 抽出 `CreditLedgerService` 负责账本写入、账本查询、消耗记录和 metadata 编解码 | `backend/src/main/java/com/aisocialgame/service/credit/CreditLedgerService.java` |
| 游戏常量散落 | 新增 gameId、phase、连接状态常量，并在 engine/runtime 入口使用 | `backend/src/main/java/com/aisocialgame/engine/*` |
| Consul 旧文档事实 | 外部 gRPC 文档改为静态地址/TLS/鉴权说明，不再把 Consul 当作当前服务发现方式 | `doc/api/external/*`、`doc/modules/*` |

## 保持不变

- HTTP 路径、请求头名称、主要响应结构不变。
- `ProjectCreditService` 继续作为账务门面，现有调用方无需改动。
- 通用转专属兑换仍保持 PENDING -> pay-service -> SUCCESS/FAILED 三段式事务边界。
- 谁是卧底、狼人杀房间页视觉结构和关键 `data-testid` 保持兼容。

## 验证

```bash
cd frontend && pnpm build
cd backend && mvn test
git diff --check
```

前端构建已在前端重构提交中通过；后端全量测试在本轮后端整改完成后执行。
