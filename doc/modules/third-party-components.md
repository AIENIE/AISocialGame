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
  - `build_common.sh`（`build.sh/build_prod.sh` 共用）
- 默认连接：
  - MySQL：`base.seekerhut.com:3306`
  - Redis：`base.seekerhut.com:6379`
  - Qdrant：`http://base.seekerhut.com:6333`

## 服务发现与域名策略

- 三服务 gRPC 默认走静态域名：
  - `static://userservice.localhut.com:10001`
  - `static://payservice.localhut.com:10021`
  - `static://aiservice.localhut.com:10011`
- SSO/HTTP 对外地址默认使用域名：
  - `userservice.localhut.com`
  - `payservice.localhut.com`
  - `aiservice.localhut.com`

## 部署脚本行为

- `build_common.sh` 会校验 `build.sh` 与 `build_prod.sh` 除默认域名外保持一致。
- `build_common.sh` 不部署、不初始化、不预检 MySQL/Redis/Qdrant；外部依赖不可用时由后端启动或业务调用暴露错误。

## Schema 稳定性

- 测试/正式部署默认使用 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`。
- 本地直启 `build_local.sh` 默认使用 `update`，仅用于开发调试。
- 新增表结构必须同步更新：
  - `backend/sql/schema.sql`
  - 对应日期迁移脚本
- v1.0 性能整改迁移脚本：`backend/sql/20260519_performance_stability.sql`。
