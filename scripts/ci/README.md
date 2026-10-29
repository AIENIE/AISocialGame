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

该阶段只解析并缓存依赖，生成 schema 为
`aienie-repository-dependency-manifest-v1` 的清单。清单绑定源码提交、目标架构、模块、工具链、
依赖输入文件 SHA-256 和缓存清单摘要。缺锁文件、空缓存、错误架构或重复输出均会失败关闭。

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

平台必须先移除缓存整树和清单的全部写位；payload 目录初始只能含固定名称、只读的仓库清单。
Build 在执行仓库 L2 编译/测试前重新验证清单、工具链、锁文件、缓存摘要和只读模式，并把缓存
复制到会在退出时清理的工作目录；Maven 使用 `-o`，npm/pnpm 使用各自的离线模式。最终再次
核对原始缓存与清单未变化，清单保留在 payload 根供平台复核并由平台在封包前移除，工作缓存不
进入产物。任何缺失输入、写权限或联网降级都会直接失败。运行时配置、秘密、证书和 Config
Center 文件都不是这两个阶段的输入，也不得进入产物。

## Production bundle

Build 阶段设置 `AIENIE_RELEASE_ENVIRONMENT=production` 后，入口生成 production Compose 和运行文件，并在 `release/sql/` 提供当前建库快照及冻结的增量 SQL 目录。

本项目已退役应用内发布授权复核、部署端签名/镜像/SQL 哈希绑定、SQL 自动执行及执行账本、恢复点生成和备份前置检查。发布前由操作者另行准备备份并选择尚未执行的 SQL；构建和启动不会自动执行这些 SQL。本轮清理没有发布 stag/prod，也没有删除其他环境已有的数据库表、执行记录或备份。

容器权限约束继续使用现有配置。运行时 TLS、鉴权、数据源、环境、标识、监听和配置覆盖校验沿用当前实现。

## 本仓库模块

- Maven: `backend`
- pnpm: `frontend`
