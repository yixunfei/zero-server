# P0-5 API Surface Gate

执行命令、稳定性分层与适用边界统一见[API 基线与兼容门禁](../operations/api-compatibility-gate.zh-CN.md)。

该目录保存首批核心模块的 bootstrap API surface。manifest 由 `scripts/VerifyPublicApiCompatibility.java --generate` 生成，包含来源 commit 与受保护模块，不使用 `target/` 临时文件作为长期基线。

首次 baseline 只是当前 `0.1.0-SNAPSHOT` 的受审计符号快照。后续删除、可见性收窄、参数/返回类型变化或 SPI 抽象性破坏必须先附迁移说明，再更新 baseline。

2026-10-05：门禁比较时忽略 `javap` 的方法级 `synchronized` 修饰符；它属于锁行为，不属于 Java 二进制方法签名。基线原文保留，线程语义变化仍需独立审查；见[本轮报告](../reports/full-reliability-review-20261005.zh-CN.md)。

2026-10-05：仅针对已确认的 `zero-data` 入口更新两个 Envelope 构造器及 `inMemory` 的必填 Executor 参数，并移除 Envelope CRUD 十个方法的 `synchronized` 符号标记；保留内存仓库的同步符号及其他基线。没有全量重新生成。行为和消费者迁移见[说明](../migrations/20261005-performance-issues.zh-CN.md)，验证见[报告](../reports/performance-issues-20261005.zh-CN.md)。

2026-10-03 复核同步了 `RuntimeAssemblyPlan` 构造器增加 `RuntimeConfigDiagnostics` 的一处既有签名变化。该变化在本轮审计基线提交中已经存在，并已记入 [2026-09-30 迁移说明](../migrations/20260930-flexibility-foundation.zh-CN.md)；此次只更正该受保护符号，不批量重建快照，不新增兼容构造器。验证证据见[并发专项报告](../reports/concurrency-async-performance-20261003.zh-CN.md)。
