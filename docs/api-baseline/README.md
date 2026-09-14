# P0-5 API Surface Gate

该目录保存首批核心模块的 bootstrap API surface。manifest 由 `scripts/VerifyPublicApiCompatibility.java --generate` 生成，包含来源 commit 与受保护模块，不使用 `target/` 临时文件作为长期基线。

首次 baseline 只是当前 `0.1.0-SNAPSHOT` 的受审计符号快照。后续删除、可见性收窄、参数/返回类型变化或 SPI 抽象性破坏必须先附迁移说明，再更新 baseline。
