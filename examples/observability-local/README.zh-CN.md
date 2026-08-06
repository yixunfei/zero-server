# 本地可观测性正反路径示例

该示例展示 O1 最小运行时的直接使用方式：

- 业务、错误、审计、安全和性能日志共享 `ZeroLogRecord` 固定字段。
- 业务只向 `LogAppender`（本示例为 `LogPipeline`）写入，`InMemoryLogSink` 只作为终端装配和测试快照。
- `STARTED / SUCCESS` 不携带 ErrorCode，`FAILURE / REJECTED` 携带真实 ErrorCode。
- 默认安全门拒绝 `token` 字段，拒绝结果不会进入终端 sink。
- 指标定义显式声明有序标签 schema，`traceId` 等高基数标签在运行时被拒绝。
- TraceId 由调用方显式传入日志，不进入指标标签。

运行：

```powershell
mvn -f examples/observability-local/pom.xml test
mvn -f examples/observability-local/pom.xml exec:java
```

稳定摘要：

```text
zero-observability-local=ok|logs=5|sensitiveRejected=ZERO-LOG-SENSITIVE-FIELD-REJECTED|labelRejected=ZERO-MONITOR-METRIC-LABEL-FORBIDDEN|stopped=true
```

示例不创建线程、端口、Docker 或外部连接，执行完成即没有残留生命周期资源。它不包含生产文件/Kafka sink、Prometheus HTTP endpoint、Grafana 部署、批量、背压、容量或 SLA 承诺。
