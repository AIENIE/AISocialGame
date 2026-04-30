# 第三方组件对齐说明

> 更新时间：2026-03-04

## 依赖清单

当前项目依赖以下外部组件（默认统一对接 `192.168.5.208` 标准端口）：

- MySQL
- Redis
- Qdrant
- Consul

## 对齐结果

- 默认配置已统一到：
  - `backend/src/main/resources/application.yml`
  - `env.txt`
  - `build_common.sh`（`build.sh/build_prod.sh` 共用）
- 默认连接：
  - MySQL：`192.168.5.208:3306`
  - Redis：`192.168.5.208:6379`
  - Qdrant：`http://192.168.5.208:6333`
  - Consul：`http://192.168.5.208:60000`

## 服务发现与域名策略

- 三服务 gRPC 默认走 consul：
  - `consul:///aienie-userservice-grpc`
  - `consul:///aienie-payservice-grpc`
  - `consul:///aienie-aiservice-grpc`
- SSO/HTTP 对外地址默认使用域名：
  - `userservice.seekerhut.com`
  - `payservice.seekerhut.com`
  - `aiservice.seekerhut.com`

## 部署脚本行为

- `build_common.sh` 在部署前会检测上述依赖可达性。
- `build_common.sh` 会校验 `build.sh` 与 `build_prod.sh` 除默认域名外保持一致。
- 若依赖未就绪，脚本直接失败退出，不会在本机拉起替代依赖。
- MySQL 库/账号引导可通过 `MYSQL_BOOTSTRAP_ENABLED` 控制。
