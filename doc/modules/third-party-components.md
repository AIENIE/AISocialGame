# 第三方组件对齐说明

## 依赖清单

当前项目依赖以下外部组件（本地环境默认统一对接 `localbase.testhut.top`）：

- MySQL
- Redis
- Qdrant

## 对齐结果

- 默认配置已统一到：
  - `backend/src/main/resources/application.yml`
  - `env.example`
  - `scripts/windows/Start-Backend.ps1`（Windows 本机调试固定端点）
- 默认连接：
  - MySQL：`localbase.testhut.top:23306`
  - Redis：`localbase.testhut.top:26379`
  - Qdrant：`http://localbase.testhut.top:26333`

## 服务发现与域名策略

- 三服务 gRPC 默认走静态域名：
  - `static://localuserservice.testhut.top:22001`
  - `static://localpayservice.testhut.top:22021`
  - `static://localaiservice.testhut.top:22011`
- SSO/HTTP 对外地址默认使用域名：
  - `localuserservice.testhut.top`
  - `localpayservice.testhut.top`
  - `localaiservice.testhut.top`

## 部署脚本行为

- 发版链路与本地调试脚本都不部署、不初始化、不预检 MySQL/Redis/Qdrant；外部依赖不可用时由后端启动或业务调用暴露错误。

## Schema 稳定性

- 测试/正式部署默认使用 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`。
- 如需本地临时调试自动改表，手动导出 `SPRING_JPA_HIBERNATE_DDL_AUTO=update` 后再启动对应进程；该模式不再有独立部署脚本。
- 新增表结构必须同步更新：
  - `backend/sql/schema.sql`
  - 对应日期迁移脚本
- v1.0 性能整改迁移脚本：`backend/sql/20260519_performance_stability.sql`。
- 管理员 TOTP 认证迁移：启动前幂等执行并核验 `backend/sql/20260810_admin_totp_auth.sql`。
