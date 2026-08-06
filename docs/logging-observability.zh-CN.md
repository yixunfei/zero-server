# 日志与可观测性设计

本文描述 zeroServer 当前已经实现的日志与监控公共模型。完整 API、接入示例和生产晋升边界见 [可观测性最小运行时](observability-runtime.zh-CN.md)。

当前状态：`minimum-slice-implemented / confirmed=true / productionReady=false`

## 1. 设计原则

- 业务、observer、GM 和示例只依赖 `LogAppender`。
- `LogSink` 仅是顶层装配使用的终端 SPI，不能绕过 `LogPipeline` 成为业务入口。
- 所有日志共享 `ZeroLogRecord`，审计日志不使用平行专用 record。
- 错误轴或非成功结果必须绑定真实 ErrorCode；开始与成功结果禁止携带 ErrorCode。
- 日志安全门不可关闭，业务扩展策略只能收紧。
- 指标 schema 在注册时冻结，样本必须完全匹配；高基数信息进入日志，不进入 Prometheus 标签。
- TraceId 由入口或调用方显式传入；日志模块不读取 ThreadLocal，也不宣称自动传播到全仓。

## 2. 日志分类

`LogType` 当前包含：

- `RUNTIME`：组件状态、生命周期和装配。
- `BUSINESS`：业务流程和结果。
- `PLAYER_BEHAVIOR`：玩家行为和数据中台事件。
- `AUDIT`：GM、配置和运营操作留痕。
- `ERROR`：协议、RPC、数据、缓存或系统失败。
- `PERFORMANCE`：关键路径耗时、队列和积压。
- `SECURITY`：鉴权、限流、敏感内容或权限拒绝。

日志类型只描述用途，不替代安全策略、ErrorCode 或业务幂等判断。

## 3. 首版固定字段

`ZeroLogRecord` 的 `schemaVersion` 固定为字符串 `"1"`。标准字段按稳定顺序输出：

```text
schemaVersion
time
level
logType
serviceName
instanceId
module
operation
result
traceId
errorCode
message
fields
```

其中：

- `serviceName / instanceId / module` 由不可变 `LogSource` 提供。
- `operation / result / errorCode` 由不可变 `LogOperation` 提供。
- `fields` 是按 key 自然序冻结的不可变 Map，也是统一扩展字段入口。
- `LogRecordFormatter` 用于本地和测试的稳定单行格式，不冻结为生产文件、JSON 或 Kafka 线格式。

首版预算为最多 32 个字段、字段名 64 字符、单字段值 4096 字符、message 4096 字符、message 与字段名值合计 16384 字符、固定标识 128 字符。

ErrorCode 唯一判定式为：

```text
requiresErrorCode = level == ERROR
                 OR logType == ERROR
                 OR result IN {FAILURE, REJECTED, TIMEOUT, DEGRADED}
```

`STARTED / SUCCESS` 不允许 ErrorCode，也不能与 `ERROR` level/type 组合。非法结构绑定 `LogErrorCode.INVALID_RECORD`。

## 4. 安全写入链路

```text
ZeroLogRecord
  -> LogAppender
  -> LogPipeline
       -> 结构、预算、ErrorCode 组合校验
       -> processor 前不可关闭安全门
       -> 有序 LogProcessor 与逐次复验
       -> sink 前不可关闭安全门
  -> terminal LogSink
```

默认策略拒绝 token、密码、secret、各类 credential、raw command、带 user-info 的 URI 和连接串凭据；默认脱敏 IP、operator、accountId、playerId、targetId。控制字符被转义为单行文本。默认脱敏值为 `[REDACTED]`，需要安全关联时可以显式使用带 keyId、至少 32 字节部署密钥的 HMAC-SHA-256 redactor。`LogIdentifierRedactor` 是 sealed 安全边界，只允许这两种框架实现，业务不能注入 passthrough 或无盐摘要实现。

HMAC redactor 把每次输入都视为不可信原值并重新计算，不会因为字符串已经符合 `hmac-sha256:keyId:64hex` 外形就原样放行。只有同一次 `append` 中首次安全门在相同消息/字段位置生成的完全相同安全值，才可凭管线内部的“位置 + 值” provenance 在终端门复用；该 provenance 不进入公开记录、字段或 sink。调用方输入、processor 新增、换位置或伪造的 HMAC 外形字符串都会重新脱敏。

processor 无法绕过终端复验；附加 `SensitiveFieldPolicy` 只能增加拒绝或脱敏规则。附加策略异常属于处理敏感原值时产生的不可信诊断，管线只暴露框架生成的安全失败，不复制原异常 message、cause、suppressed 或可注入的 stack trace 字段。processor/sink 失败仍分别绑定 `PROCESSOR_FAILED` / `SINK_FAILED` 并按其独立契约保留 cause，管线不递归记录自身失败。

当前内置 `InMemoryLogSink` 和 `SystemLoggerLogSink` 适合本地原型、测试和启动日志。生产文件滚动、Kafka sink、批量、背压、失败重试与降级尚未实现。

## 5. 指标与告警

当前监控公共模型包括：

- `MetricDefinition(name, description, unit, labelNames)`：显式有序标签 schema，最多 8 个标签。
- `MetricSample`：值、标签和值发生时间；标签必须与定义 schema 完全匹配。
- `MetricRegistry` / `InMemoryMetricRegistry`：定义注册、样本记录和不可变快照。
- `SystemMetricCollector`：显式采集 JVM 内存、线程、GC、CPU、磁盘和网络接口状态。
- `PrometheusExporter`：确定性 Prometheus 文本导出，不内置 HTTP 服务。
- `GrafanaDashboardTemplate`：最小 dashboard JSON 模板生成。
- `AlertRule` / `AlertEvaluator` / `AlertSink`：本地告警评估与落地扩展点。
- `PrometheusAlertRuleExporter`：Prometheus alert rules YAML 导出。
- `MonitorRuntime`：组合本地注册表、系统采集、导出器和告警评估器；不创建后台线程。

同名同定义注册幂等；同名不同定义拒绝。全局策略禁止 TraceId、SpanId、玩家/账号/连接/请求/房间/场景/目标/操作者/审批标识、token、IP 和 remote address 类标签，也禁止完整 IPv4/IPv6 标签值。`MetricLabelPolicy` 只能收紧该底线。

`PrometheusExporter` 按定义中的标签顺序输出，显式处理 `NaN / +Inf / -Inf`，不会静默忽略非法样本。`MonitorRuntime.collectOnce()` 返回包含系统探针报告和告警事件的 `MonitorCollectionResult`；单探针失败只保留探针枚举和异常类型名。

推荐本地调用链：

```text
MonitorRuntime.createDefault(rules, sinks)
  -> collectOnce()
  -> exportPrometheus()
  -> exportGrafanaDashboard(title, metrics)
```

严重监控告警通过 `MonitorAlertLogSink` 写入 `LogAppender`，绑定真实 `MonitorErrorCode.CRITICAL_ALERT_TRIGGERED`，不再使用通用系统失败占位码。

## 6. GM 审计

`GmAuditAttributionFactory` 在事件构造前把 operator、来源、approval 和 target 转换为固定脱敏或域分隔 HMAC 安全引用。`GmAuditEvent` 不持有原 context、raw command、参数值、原身份/target、roles、permissions、attributes 或原异常 message。

`GmAuditRecordFactory` 把事件转换为 `LogType.AUDIT` 的 `ZeroLogRecord`，`LoggingGmAuditHook` 只依赖 `LogAppender`。非成功 GM 结果携带真实 ErrorCode，并使用四态提交语义：

- `NOT_APPLICABLE`：dry-run。
- `NOT_COMMITTED`：handler 前失败或明确无副作用拒绝。
- `COMMITTED`：handler 成功返回；后置审计失败也保持该状态。
- `UNKNOWN`：handler 抛错、返回 null 或非法结果，无法证明是否提交。

`COMMITTED` 和 `UNKNOWN` 禁止自动重试。该能力不包含 RBAC、IP 白名单、完整审批流或后台鉴权。

## 7. 生产连接生命周期遥测

`zero-net` 继续只暴露中立的 `ConnectionLifecycleObserver`，不依赖 `zero-log` 或 `zero-monitor`。`zero-server-starter-production` 的 `ProductionNetworkTelemetryObserver` 组合 Starter 提供的 `LogAppender` 与 `MetricRegistry`。

每个 Netty production 连接在共享受管 observer executor 之前维护一个轻量有序 drain：同一连接最多提交一个活动 drain，并按会话提交顺序执行 observer 事件；不同连接仍可由共享执行器并发处理。这保证延迟或多线程执行器不会把同一连接的 close 遥测执行到此前的 accept 遥测之前。当前单连接 observer 待执行队列没有独立容量上限、背压或丢弃策略，慢 observer 仍可能造成积压，因此该顺序保证不构成容量、长稳或 production ready 证明。

连接日志可以记录显式 TraceId、脱敏引用、受控状态/事件/结果、拒绝原因、限流范围和阶段耗时；不得记录 token、密码、密钥、完整 IP、玩家对象、房间或场景原值。拒绝与限流进入安全日志，执行失败进入错误日志，握手/鉴权成功进入性能日志；失败绑定真实 `NetErrorCode`。

网络指标标签固定为 `listener / protocol / event / result / reason / scope` 的低基数组合。TraceId、playerId、connectionId、完整 IP、token、roomId 和 sceneId 不进入指标标签。

## 8. 本地接入与验证

无 Docker 正反路径示例：

```powershell
mvn -q -DskipTests install
mvn -q -f examples/observability-local/pom.xml test
mvn -q -f examples/observability-local/pom.xml exec:java
```

可复制片段位于 `templates/observability-snippet`。业务代码只保存 `LogAppender`，指标定义必须显式声明 `labelNames`。

仅需性能证据时，显式启用叶子模块：

```powershell
mvn -Pbenchmarks -pl :zero-benchmarks -am -DskipTests package
```

`zero-benchmarks` 使用 JMH 1.37，不进入默认 reactor，也不设置 CI 阈值。production network observer 的 current/candidate 只共享外层场景、输入、JDK 和 JMH 参数；current 直写 terminal `LogSink`，candidate 经过真实 `LogPipeline`，内部工作量实质不同，只能方向性观察新增安全语义成本，不能计算回退/改善比例。现有结果不能证明吞吐、p99、容量、长稳、SLA 或 production ready。

## 9. 当前边界

当前没有 production file/Kafka sink，没有 Prometheus HTTP endpoint，没有容量或长稳证明，没有 SLO/SLA，也没有完整 GM 安全治理。因此该切片虽然已经实现最小运行时，仍明确为 `productionReady=false`。
