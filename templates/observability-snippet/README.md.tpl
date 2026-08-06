# 可观测性接入片段

把 `ObservabilityModule.java` 中的 `{{packageName}}` 替换为业务包名。示例指标名固定使用合法的
`zero_game_operations_total`；复制到业务项目时，可以按 Prometheus 命名规则替换为项目级低基数名称。

装配边界创建一次安全管线：

```java
InMemoryLogSink terminalSink = new InMemoryLogSink();
LogAppender logAppender = new LogPipeline(List.of(), terminalSink);
InMemoryMetricRegistry metricRegistry = new InMemoryMetricRegistry();
ObservabilityModule observability = new ObservabilityModule(
        logAppender,
        metricRegistry,
        "{{artifactId}}",
        "local-1");
```

业务代码只保存 `LogAppender`，不要直接保存或调用 terminal `LogSink`。成功日志不能携带 ErrorCode；失败日志必须传入真实 ErrorCode。

指标标签必须在 `MetricDefinition.labelNames()` 中有序声明并完全匹配。`traceId`、`playerId`、`accountId`、`connectionId`、完整 IP 等高基数字段只能进入日志或受控数据链路，不能进入指标标签。

默认安全门会拒绝 token、密码、secret、凭据连接串和 raw command，并默认脱敏 IP、operator、accountId、playerId、targetId。业务附加 `SensitiveFieldPolicy` 只能收紧，不能放宽框架底线。

该片段是 local / single-process / minimum-slice 接入，不包含生产文件或 Kafka sink、Prometheus HTTP endpoint、批量、背压、Grafana 部署、容量或 SLA 保证。
