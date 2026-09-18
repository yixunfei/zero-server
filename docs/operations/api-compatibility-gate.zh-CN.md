# API 基线与兼容门禁

适用版本：`0.1.0-SNAPSHOT`。当前门禁保护 `zero-core`、`zero-runtime`、`zero-protocol`、`zero-rpc-common`、`zero-data` 的 bootstrap 公共 API 快照，允许新增符号，拒绝相对基线的删除或签名变化。

## 执行

在仓库根目录使用 JDK 21、Maven 3.9+：

```bash
mvn -B -ntp -DskipTests -pl zero-core,zero-runtime,zero-protocol,zero-rpc-common,zero-data -am install
java scripts/VerifyPublicApiCompatibility.java --check
java scripts/VerifyApiCompatibilityConsumer.java
```

第一步编译并安装保护模块；第二步比较公共符号；第三步编译独立消费者。GitHub Actions 的 `compatibility-gate` 执行同类检查。基线存放在[api-baseline](../api-baseline/README.md)，不依赖本地 `target/` 文件作为长期基线。

## 稳定性与 0.x 变更

文档使用以下稳定性分层；这些名称是维护约定，不表示所有源码已具有稳定性注解或完整自动检查：

| 分层 | 含义 |
| --- | --- |
| STABLE | 纳入保护基线的核心公共契约；破坏性变化必须提供迁移说明 |
| INCUBATING | 仍可能在 0.x 调整的扩展点，变更需记录和评审 |
| EXPERIMENTAL | 明确实验性；若已纳入基线，同样接受门禁检查 |
| DEPRECATED | 提供替代路径与迁移要求，删除前记录影响 |

需要破坏性调整时，先写 Changelog 与[具体迁移说明](../migrations/README.md)，再审阅并更新基线。不要用重新生成基线掩盖未审阅的失败。生成入口为 `java scripts/VerifyPublicApiCompatibility.java --generate`。

## 能证明的范围

当前 baseline 是开发预览的符号快照，不是历史发行版，也不构成完整二进制兼容承诺。独立消费者只覆盖其使用的 API；配置 key、协议 ID、provider ID、所有模块及完整 ABI 差异尚未纳入。后续增强见[路线图 P0-5](../optimization-roadmap.zh-CN.md)。
