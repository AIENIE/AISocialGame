# 配置文件管理

配置格式为 `application-yaml-env-v1`。`application.yml` 存放数据库地址、用户名、连接参数、端点、ENV/AUTH_MODE、模型及目录；`env.txt` 仅存放密码、哈希、令牌和密钥。管理员用户名在 YAML，密码或哈希在 env.txt。管理员凭据仍执行原校验，test/production 强制 TOTP。

本地使用仓库现有 `application-local.yml`。启动脚本保留 `-EnvFile` 或 `-EnvironmentFile` 自定义路径；私密文件放在仓库外。需要独立配置时，在该私密文件旁保存 `<私密文件名>.application.yml`；启动器将它作为 Spring 外部配置加载。临时覆盖继续使用 Spring 原生属性参数。秘密按字面值读取，不执行 shell 展开；不要在秘密文件中保存进程控制选项。

服务器由控制台按项目和环境保存、编辑、上传及下载 `application.yml` 和 `env.txt`。配置以只读方式挂载；env.txt 必须为 owner-only（0600/0400）。外部 YAML 优先于仓库 profile。不要使用 Compose 的 `env_file` 解析秘密，或将完整秘密文件复制到 Compose 插值文件。

制品携带 `.aienie-runtime-config-format` 标识和 `scripts/config-pair` 读取工具，沿用现行配置对和挂载校验。

本目录的 `config_pair.py` 仅保留现行读取器使用的解析与校验函数。历史配置转换由中央工具库维护；仓内旧迁移、备份及回滚副本已删除。
