# 第三方组件对齐说明

> 更新时间：2026-03-04

## 依赖清单

当前项目依赖以下外部组件（默认统一对接 `192.168.5.208` 标准端口）：

- MySQL
- Redis
- Qdrant

## 对齐结果

- 默认配置已统一到：
  - `backend/src/main/resources/application.yml`
  - `env.txt`
  - `build.sh`
- 默认连接：
  - MySQL：`base.seekerhut.com:3306`
  - Redis：`base.seekerhut.com:6379`
  - Qdrant：`http://base.seekerhut.com:6333`

## 服务发现与域名策略

- 三服务 gRPC 默认走静态域名：
  - `static://userservice.seekerhut.com:443`
  - `static://payservice.seekerhut.com:443`
  - `static://aiservice.seekerhut.com:443`
- SSO/HTTP 对外地址默认使用域名：
  - `userservice.seekerhut.com`
  - `payservice.seekerhut.com`
  - `aiservice.seekerhut.com`

## 部署脚本行为

- `build.sh` 不部署、不初始化、不预检 MySQL/Redis/Qdrant；外部依赖不可用时由后端启动或业务调用暴露错误。

## Schema 稳定性

- 测试/正式部署默认使用 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`。
- 如需本地临时调试自动改表，手动导出 `SPRING_JPA_HIBERNATE_DDL_AUTO=update` 后再启动对应进程；该模式不再有独立部署脚本。
- 新增表结构必须同步更新：
  - `backend/sql/schema.sql`
  - 对应日期迁移脚本
- v1.0 性能整改迁移脚本：`backend/sql/20260519_performance_stability.sql`。
