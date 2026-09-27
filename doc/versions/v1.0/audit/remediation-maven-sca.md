# Maven 全依赖 SCA 复验（2026-09-27）

审计项 SEC-008。当前代码、扫描门禁、最终 L2 与本地可执行制品依赖复验已通过；真实发布目标数据库版本仍须作为独立发布前门禁核实。NVD API Key 取自本机环境变量；密钥未写入仓库或日志。

## 实际解析与门禁

AISocialGame 已升级 Spring Boot 4.1.1，使用官方 gRPC 客户端 starter、Jackson 2 兼容模块和 Connector/J 26.7.0。发布构建使用 Maven 3.9.16；本地测试使用 Maven 3.9.12。Protobuf 生成器与运行库同为 3.25.9，gRPC 调用的认证、TLS、超时与取消有定向测试。隔离 MySQL 8.4.11 的迁移和行为回归通过；发布前仍须只读确认目标数据库为 Server 8.4+。

`security-audit` profile 扫描运行、测试与 provided 依赖，并以 CVSS 7 阻断高危和严重公告。ODC 13 的内置 `scanPlugins` 依据插件原始 POM 解析，无法体现当前项目的插件依赖覆盖；因此 CI 从 Maven `dependency:resolve-plugins` 导出实际解析，`prepare-maven-plugin-sca.py` 将 12 个显式固定插件的 223 个不同 JAR 连同坐标、路径、SHA-256 加入扫描，`verify-maven-plugin-sca.py` 在扫描后核对每个摘要均出现在 ODC 报告内。POM、例外文件或制品在解析后变化均使门禁失败。

实际依赖中 `httpclient5-cache` 升至 5.6.4；protobuf 构建插件的 Maven Core 依赖升至 3.9.12。Boot、编译、资源等插件的 HttpClient、Plexus Utils 与 BeanUtils 覆盖同样由解析清单核对。扫描日志为 `evidence/remediation-effective-sca-final.log`，扫描报告为 `backend/target/dependency-check-report.json`。该日志显示 223 个插件 JAR 均被扫描，CVSS 7 及以上告警在精确例外后为零。

## 精确误报登记

责任人：AISocialGame 后端维护者。复审到期：2026-12-31。例外仅在以下实际解析 JAR 的 SHA-1 与列出的 CVE 同时匹配时有效；换版本、换文件或到期即重新阻断。`prepare-maven-plugin-sca.py` 拒绝无责任人、无到期日、通配/宽泛选择器、未在解析插件中出现的摘要。

| 制品 | SHA-1 | CVE | 适用性依据 |
|---|---|---|---|
| `net.java.dev.jna:jna:5.17.0` | `33d12735bef894440780fce64f9758d420c7bae2` | CVE-2009-2689、2475、2476、2716、2676、2717、2690、2719、2720 | ODC 将 Java Native Access 的 5.17.0 误映射为 Oracle/Sun Java SE 产品。JNA 是独立库；[JNA 项目说明](https://github.com/java-native-access/jna)。 |
| `org.tukaani:xz:1.9` | `1ea4bec1a921180164852c65006d928617bd2caf` | CVE-2015-4035、2022-1271、2026-34743 | 命中的是 XZ Utils 的 `xzgrep`/C 库，不是纯 Java 的 XZ for Java。 |
| `org.tukaani:xz:1.11` | `bdfd1774efb216f506f4f3c5b08c205b308c50aa` | 同上 | 同上。 |
| `org.tukaani:xz:1.12` | `bb9703ba3753ab8665f65e6a25b3ddc7b09b1caf` | 同上 | 同上。 |

XZ 项目明确记录了扫描器把 XZ Utils CPE 错配到 [XZ for Java](https://tukaani.org/xz/java.html) 的情况。例外格式遵循 [OWASP 精确 SHA/CVE 与到期规则](https://dependency-check.github.io/DependencyCheck/general/suppression.html)。扫描中仍有低于阻断线的 Commons IO、Commons Lang 与 Surefire Shared Utils 告警；后续依赖维护应继续处理，不能将它们归入上述例外。

## 验收边界

本项门禁覆盖当前本地解析结果。`Test-Local.ps1 -Level L2` 通过：后端 585 项中 547 通过、38 个真实环境门禁跳过，前端 72 项通过；证据为 `evidence/remediation-final-l2-20260927.log`。本地可执行 JAR 内依赖版本与摘要见 `evidence/remediation-boot4-packaged-libraries.json`。MySQL 连接测试只证明本项目内 Server 8.4.11 兼容，不能代替未来共享数据库版本核查。本轮不部署预发布或生产。
