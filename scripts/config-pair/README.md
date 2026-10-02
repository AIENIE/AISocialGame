# 配置文件管理

配置格式为 `application-yaml-env-v1`。`application.yml` 存放数据库地址、用户名、连接参数、端点、ENV/AUTH_MODE、模型及目录；`env.txt` 仅存放密码、哈希、令牌和密钥。管理员用户名在 YAML，密码或哈希在 env.txt。管理员凭据仍执行原校验，test/production 强制 TOTP。

本地使用仓库现有 `application-local.yml`。启动脚本保留 `-EnvFile` 或 `-EnvironmentFile` 自定义路径；私密文件放在仓库外。需要独立配置时，在该私密文件旁保存 `<私密文件名>.application.yml`；启动器将它作为 Spring 外部配置加载。临时覆盖继续使用 Spring 原生属性参数。秘密按字面值读取，不执行 shell 展开；不要在秘密文件中保存进程控制选项。

服务器由控制台按项目和环境保存、编辑、上传及下载 `application.yml` 和 `env.txt`。FireflyChat 主站与 Studio 使用各自配置对，根目录中的 Studio 配置名为 `studio.application.yml` 和 `studio.env.txt`。配置以只读方式挂载；env.txt 必须为 owner-only（0600/0400）。外部 YAML 优先于仓库 profile。不要使用 Compose 的 `env_file` 解析秘密，或将完整秘密文件复制到 Compose 插值文件。

制品携带 `.aienie-runtime-config-format` 标识和 `scripts/config-pair` 发布工具。停止实例前验证配置对、只读挂载和制品格式。新格式必须搭配本轮源码构建的制品及更新后的控制平面；历史版本继续使用自己的制品、配置快照及部署脚本回退。迁移加入的临时 Compose 保护记录由更新后的控制平面在创建新发布快照时跳过，旧控制平面会提前拒绝该配置。

迁移工具在 `aienie-infra/control-plane/tools/config_pair.py`（本地文件）及 `migrate_config_center.py`（控制台记录）。先预检，备份原文件/记录，生成候选，再写入并读回两文件，最后删除管理员文件。失败恢复该项目该环境原值；重复执行不创建重复记录。备份及历史发布快照保留秘密并需保持私密权限，迁移报告仅包含键名、数量与状态。迁移工具不启动或发布服务。

PDFConverter Desktop 不适用此服务配置链路。
