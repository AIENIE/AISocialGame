# Aienie 两阶段发布构建契约

发布平台以仓库内 `scripts/ci/build-release.sh` 作为唯一入口。入口只接受一个位置参数，且必须与
`AIENIE_CI_OUTPUT_DIR` 指向同一目录。

## Resolve

Resolve 节点可以访问批准的软件仓库，但不能持有业务配置或发布凭据：

```bash
AIENIE_CI_PHASE=resolve \
AIENIE_CI_SOURCE_COMMIT=<immutable-commit> \
AIENIE_CI_TARGET_ARCHITECTURE=linux/arm64 \
AIENIE_CI_CACHE_DIR=<empty-cache-dir> \
AIENIE_CI_OUTPUT_DIR=<empty-resolve-output-dir> \
AIENIE_DEPENDENCY_MANIFEST=<resolve-output-dir>/repository-dependency-manifest.json \
scripts/ci/build-release.sh <resolve-output-dir>
```

该阶段缓存 Maven/pnpm 依赖，并执行原有依赖漏洞扫描、阈值和豁免范围检查（S05）。输出 `aienie-repository-dependency-manifest-v1` 格式的交接记录，供中央构建流程归档；记录源码标签、模块、工具版本和依赖摘要。

## Build

Build 节点必须断网，并消费 Resolve 的原样缓存和清单：

```bash
AIENIE_CI_PHASE=build \
AIENIE_CI_NETWORK_MODE=offline \
AIENIE_CI_SOURCE_COMMIT=<same-immutable-commit> \
AIENIE_CI_TARGET_ARCHITECTURE=linux/arm64 \
AIENIE_CI_CACHE_DIR=<read-only-resolved-cache-dir> \
AIENIE_CI_OUTPUT_DIR=<payload-dir-containing-only-manifest> \
AIENIE_DEPENDENCY_MANIFEST=<payload-dir>/repository-dependency-manifest.json \
scripts/ci/build-release.sh <payload-dir>
```

Build 复制 Resolve 的依赖缓存到临时工作目录，使用 Maven/pnpm 离线模式执行编译、类型检查、lint 和单元测试，再交给统一封包工具。S09 已退役仓库内源码、工具版本、锁文件和缓存摘要的一致性复核、输入只读状态检查及构建后的重复复核；原有漏洞扫描结果检查继续保留。交接记录在封包时暂存，完成后放回输出目录，工作缓存不会进入产物。

S10 已退役预生产 Compose/OCI 的打包前复核及其专用角色清单。中央构建目录仍定义后端、前端镜像角色；Compose 中现行的 TLS、鉴权、端口、容器权限和资源配置继续使用。

## Production bundle

Build 阶段设置 `AIENIE_RELEASE_ENVIRONMENT=production` 后，入口生成 production Compose 和运行文件，并在 `release/sql/` 提供当前建库快照及冻结的增量 SQL 目录。

本项目已退役应用内发布授权复核、部署端签名/镜像/SQL 哈希绑定、SQL 自动执行及执行账本、恢复点生成和备份前置检查。发布前由操作者另行准备备份并选择尚未执行的 SQL；构建和启动不会自动执行这些 SQL。本轮清理没有发布 stag/prod，也没有删除其他环境已有的数据库表、执行记录或备份。

容器权限约束继续使用现有配置。运行时 TLS、鉴权、数据源、环境、标识、监听和配置覆盖校验沿用当前实现。

## 本仓库模块

- Maven: `backend`
- pnpm: `frontend`
