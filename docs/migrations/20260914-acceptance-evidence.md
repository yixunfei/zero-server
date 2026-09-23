# 20260914 验收证据入口迁移说明

## 变更

新增 `java scripts/ZeroAcceptanceEvidence.java`，用于生成 `target/acceptance-evidence/` 下的 WP-00 机器可读验收包：

- `evidence.json`：仓库 revision、分支、dirty/untracked、风险文件和检查记录；
- `records.jsonl`：逐项记录；
- `capabilities.json`：统一 capability 状态（`implemented`、`implemented-partially-proven`、`not-proven` 等），并分别记录 implementation、verification 和 remaining gap；
- `environment.txt` 与 `logs/`：环境和原始输出。

状态使用 `passed`、`failed`、`skipped`、`missing`、`blocked`。当必需命令缺失或阻塞时工具返回非零退出码；历史 marker 或旧日志不构成执行证据。工具只写入 `target/`，不会修改源码。

## 使用方式

```text
java scripts/ZeroAcceptanceEvidence.java --level quick
java scripts/ZeroAcceptanceEvidence.java --level full
java scripts/ZeroAcceptanceEvidence.java --level quick --no-stage0
```

默认仍保持现有 Stage 0、Doctor 和各守卫的命令/输出兼容。CI 新增 `acceptance-evidence` job，并上传同名 artifact。

`ZeroAcceptanceEvidence` 还会执行 `ProjectScaffoldCli` 进程级退出码矩阵：`0` 成功、`1` 普通生成失败、`2` 参数解析失败、`3` plan/升级阻塞。每个场景独立分流 stdout/stderr，并在 `cli-exit-matrix/` 保存原始日志、预期/实际退出码和稳定错误码匹配结果。

`ZeroAcceptanceEvidence` 还会执行 `matrix.actor-async-thread-contract`：在 Java 21 下运行 `zero-actor` 与 `zero-runtime-bootstrap` 的 direct/async/Actor focused tests，覆盖成功、异常、取消、超时、未完成 stage 阻塞检测、executor 拒绝和受管执行器关闭。该 record 的原始输出写入 `logs/actor-async-thread-contract.log`，只代表本地线程契约证据，不代表生产就绪。

`matrix.empty-runtime`、`matrix.event-actor` 和 `matrix.config-lint` 也已接入真实本地命令：前两项运行模块化 consumer focused tests，后一项运行静态 `ZeroConfigLint`；三项均分别保存原始日志，不创建外部连接或隐式线程。

`matrix.adapters` 与 `matrix.mixed-composition` 通过独立 verifier 接入：前者运行 discovery/Redis consumer 与 production adapter contract focused tests，后者运行 mixed 生成工程验证；原始日志分别保存为 `logs/adapters.log` 与 `logs/mixed-composition.log`。

`matrix.no-sdk-external-service` 通过一次性 clean-room Maven consumer 接入，检查禁止 SDK/artifact 和最小 runtime 无外部副作用，原始日志保存为 `logs/no-sdk-external-service.log`。

`matrix.platform-transaction` 与 `production.local-focused` 现由 `ZeroPlatformProductionGate` 实际执行并保存新鲜 manifest/log；CI 通过 Ubuntu/macOS/Windows matrix 收集 platform transaction，不能用当前 Windows 结果替代其他 OS。Kafka 双 JVM 命令若输出 `center-logic-kafka=blocked`，记录状态为 `blocked` 而非 `failed`；blocked 仍使验收整体为 `incomplete` 和非零退出。 当前 Windows fresh run 已取得 Java 21 + Docker daemon unavailable 的 blocked manifest；真实双 JVM assertion 仍未运行。

这是 0.x 的新增工具和证据格式，不改变 runtime、公共 API、协议、配置优先级或 `productionReady`。现阶段真实 TCP 长驻链路、双进程 Kafka、生成器事务升级和生产能力仍记录为 `not-proven`，不得据此推断完成。
