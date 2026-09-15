# 20260915 no-SDK clean-room 与 Kafka blocked 证据

## 变更

- 新增 `scripts/VerifyNoSdkExternalService.java`，在 `target/no-sdk-external-service` 生成一次性 Maven consumer。该 consumer 只依赖 `zero-runtime-bootstrap`（传递获得 core/runtime/bootstrap），不依赖 starter、codegen、Netty、Kafka、Mongo、PostgreSQL、Redis、Nacos 或其 SDK，并执行最小 runtime start/close。
- verifier 构建 runtime classpath，检查禁止 artifact，保存 `target/no-sdk-external-service/maven.log` 和依赖 classpath，再输出稳定 marker。
- `ZeroAcceptanceEvidence` 接入 `matrix.no-sdk-external-service`，原始命令日志为 `target/acceptance-evidence/logs/no-sdk-external-service.log`。
- Kafka 外部依赖不可用时，`matrix.center-logic` 现在明确写为 `blocked`，reason 为 `external Kafka/Docker prerequisite unavailable`；代码/测试失败仍为 `failed`。blocked 会使总证据为 `incomplete` 并保持非零退出，不会伪造通过。

## 验证

Java 21 下：

```text
java scripts/VerifyNoSdkExternalService.java
java scripts/ZeroAcceptanceEvidence.java --level quick --no-stage0 --maven mvnw.cmd
```

结果：

```text
no-sdk-external-service=ok|artifacts=core,runtime,bootstrap|classes=forbidden-absent
matrix.no-sdk-external-service = passed
matrix.center-logic = blocked (Docker/Kafka prerequisite unavailable)
zero-acceptance-evidence=incomplete|failed=0|blocked=1|missing=0
```

`VerifyCenterLogicKafka.ps1 -Plan` 仅验证计划结构；真实双 JVM 链路只有 broker/Docker 可用且所有 request/response、drain、stop、resource assertions 通过时才会成为 `passed`。

## 回滚与未覆盖风险

删除 no-SDK verifier、evidence record 和本迁移说明即可回滚。保留 Kafka blocked 语义和非零门禁，避免将外部环境缺失误判为代码失败或通过。no-SDK 切片不证明真实外部服务连接/恢复、三平台 runner、容量/长稳、TLS/认证、GM 审计、持久化恢复或发布回滚；`productionReady=false`、`goalAchieved=false` 继续有效。
