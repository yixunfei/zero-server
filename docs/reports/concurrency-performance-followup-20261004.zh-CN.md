# 并发与性能专项复核（2026-10-04）

基线提交 `a4d254ae23242acfa552fa48a4a04a3261198f87`，分支 `zero/concurrency-async-performance-audit`，工作树保留 2026-10-03 审计与脚手架的未提交改动。验证使用本机 JDK 21、Maven Wrapper 3.9.8、Windows。此报告只记录本轮新增审查和验证；历史修复清单见[上一轮报告](concurrency-async-performance-20261003.zh-CN.md)。

## 核查与结论

复核上一轮修复、归档验证、事件总线注册/异步续调、帧对局单 lane 状态与容量、AOI 网格查询及观察快照，并重跑全仓门禁。确认一个新增缺陷：`FrameMatchRuntime` 的 `MARK` 策略消费后保留空玩家映射，参与者容量被永久占用。容量为 1 的回归先失败并抛出 `participant history capacity exceeded`；修复后空映射回收，新玩家可进入下一帧。`REPEAT_LAST` 仍保留重复上一输入所需的历史。与此同时，非重复策略不再逐帧复制无用的 `lastInputs` 映射；这是确定性的少一次映射复制结论，没有独立吞吐或延迟测量，不宣称性能幅度。

本轮没有发现其他可独立复现的新线程安全问题。全量测试与静态门禁不能证明不存在竞态，也不替代外部集群验证。

## 验证

- JDK 21 定向 `mvnw.cmd -B -ntp -pl zero-frame-sync,zero-event -am test` 通过；帧同步 12 个测试，包含新增回归及三策略有序模型。
- `mvnw.cmd -B -ntp -Pquality,benchmarks,integration-tests verify` 通过，61 个模块均为 SUCCESS；日志 `target/concurrency-followup-quality.log`。Checkstyle、PMD、SpotBugs、JaCoCo 和本地 Failsafe 按 profile 运行。
- `java scripts/ZeroArchitectureGuard.java`：61 模块、21 规则、0 违规、0 警告。
- `java scripts/VerifyPublicApiCompatibility.java --check`：5 个受保护模块通过；`java scripts/VerifyApiCompatibilityConsumer.java`：Java 21 consumer 通过。

## 边界

未重跑 Redis/PostgreSQL 外部服务用例，也未执行 Kafka/Nacos 集群、跨机故障、长期容量压测或生产负载测量；这些结论仍限于[上一轮验证](../migrations/20261003-concurrency-async-performance.zh-CN.md)及其当时环境。帧对局、AOI 和状态同步依赖单 owner 调用契约；同步 SPI 的阻塞与线程安全由应用保证。`productionReady=false` 状态未改变。
