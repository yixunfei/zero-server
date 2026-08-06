# zeroServer 可观测性最小运行时

本文说明 `observability-minimum-field-runtime` 方案 O1 的实际运行时 API、接入方式和剩余边界。

状态：`minimum-slice-implemented / confirmed=true / productionReady=false`

该切片已经把统一日志字段、安全写入边界、指标标签 schema 和 GM 安全审计归因落到运行时代码；它没有提供生产日志落地、Prometheus HTTP endpoint、容量或长稳结论。

## 1. 适用范围

当前最小运行时适合：

- 在本地原型、单进程服务和 focused tests 中使用同一套结构化日志 API。
- 让业务、observer、GM、热重载和受管调度器只依赖安全日志写入端口。
- 在注册指标时冻结有序标签 schema，并在记录样本时拒绝高基数标签和 schema 漂移。
- 在 GM 事件构造前把 operator、来源地址、审批单和目标转换为安全引用。
- 用显式 ErrorCode 和四态提交结果描述失败，避免把未知提交状态误判为可重试。

当前不包含：

- 生产文件滚动、Kafka 日志 sink、异步批量、背压、重试或降级策略。
- Prometheus HTTP endpoint、完整 Grafana 部署、远程告警通道或告警静默/去重。
- OpenTelemetry 自动埋点，或 TraceId 在 RPC、DB、MQ、Actor、线程池之间的全仓自动传播。
- GM REST/RPC 入口、RBAC、IP 白名单、完整审批流和运营后台。
- 吞吐、p99、容量、长稳、SLO 或 SLA 证明。

## 2. 日志端口隔离

日志运行时把业务写入端口与终端落地 SPI 分开：

```text
业务 / observer / GM / 示例
  -> LogAppender
  -> LogPipeline
       -> 结构、预算和 ErrorCode 校验
       -> processor 前不可关闭安全门
       -> 有序 LogProcessor，并逐次复验记录
       -> sink 前不可关闭终端安全门
  -> LogSink
       -> InMemory / System.Logger / 后续生产 Adapter
```

- `LogAppender` 是业务安全写入端口。正式调用方只保存和调用这个接口。
- `LogPipeline` 实现 `LogAppender`，但不实现 `LogSink`。它同步执行，不创建线程池、不读取 ThreadLocal，也不隐式切换执行域。
- `LogSink` 是终端装配 SPI，本身不执行结构校验或敏感字段清洗。它只能由顶层装配层传给 `LogPipeline`，不能作为业务、observer 或 GM 的直接依赖。
- `ZeroRuntimeBuilder` 通过 `terminalLogSink(...)` 接收终端 SPI；首次调用 `logAppender()` 时创建并冻结唯一安全管线，`build()` 复用同一实例。
- `ZeroRuntimeComponents` 只向业务暴露 `logAppender()`。配置热重载、受管调度器和 production network observer 同样注入 `LogAppender`。
- Netty production lifecycle 会话先通过每连接有序 drain 把 observer 事件提交给共享受管 executor；同一连接保持提交顺序，不同连接仍可并发。该 drain 不创建线程池，也不改变共享执行器生命周期。

顶层装配仍可以选择终端 sink，但业务侧只接收安全端口：

```java
InMemoryLogSink terminalSink = new InMemoryLogSink();
LogAppender logAppender = new LogPipeline(List.of(), terminalSink);
```

## 3. `schemaVersion=1` 固定日志字段

`ZeroLogRecord.create(...)` 创建不可变记录。首版固定 schema 的字段和稳定输出顺序如下：

| 顺序 | 字段 | 运行时来源 | 约束 |
| ---: | --- | --- | --- |
| 1 | `schemaVersion` | `ZeroLogRecord.SCHEMA_VERSION` | 固定为字符串 `"1"` |
| 2 | `time` | `Instant` | 必填 |
| 3 | `level` | `LogLevel` | `TRACE / DEBUG / INFO / WARN / ERROR` |
| 4 | `logType` | `LogType` | `RUNTIME / BUSINESS / PLAYER_BEHAVIOR / AUDIT / ERROR / PERFORMANCE / SECURITY` |
| 5 | `serviceName` | `LogSource` | 必填稳定标识 |
| 6 | `instanceId` | `LogSource` | 必填实例标识 |
| 7 | `module` | `LogSource` | 必填模块标识 |
| 8 | `operation` | `LogOperation` | 必填稳定操作名 |
| 9 | `result` | `LogResult` | `STARTED / SUCCESS / FAILURE / REJECTED / TIMEOUT / DEGRADED` |
| 10 | `traceId` | 调用方显式传入 | 必填；只进入日志，不进入默认指标标签 |
| 11 | `errorCode` | `LogOperation` | 按下述唯一判定式必填或禁止 |
| 12 | `message` | 调用方 | 有界安全摘要，不承载凭据正文 |
| 13 | `fields` | 调用方 | 按 key 自然序冻结的不可变扩展字段 |

日志分类只通过 `logType()` 读取，扩展数据统一使用 `fields()`；审计同样复用 `ZeroLogRecord`。`module` 是首版固定字段，由 `LogSource` 提供。

### 3.1 ErrorCode 唯一判定式

当以下任一条件成立时，记录必须携带真实 ErrorCode：

```text
level == ERROR
  OR logType == ERROR
  OR result IN {FAILURE, REJECTED, TIMEOUT, DEGRADED}
```

`STARTED` 和 `SUCCESS` 禁止携带 ErrorCode，也禁止与 `ERROR` level/type 组合。结构、预算或组合非法时抛出 `ZERO-LOG-INVALID-RECORD`；processor 和 sink 失败分别绑定 `ZERO-LOG-PROCESSOR-FAILED` 与 `ZERO-LOG-SINK-FAILED` 并保留 cause，管线不会递归记录自身失败。

### 3.2 字段预算

首版记录使用固定上限，避免无界对象和日志正文进入热路径：

| 项目 | 上限 |
| --- | ---: |
| 扩展字段数量 | 32 |
| 字段名长度 | 64 字符 |
| 单字段值长度 | 4096 字符 |
| message 长度 | 4096 字符 |
| message 与字段名值总长度 | 16384 字符 |
| service、instance、module、operation、traceId 等固定标识 | 128 字符 |

控制字符在安全管线中转义为单行文本；`fields()` 按 key 排序、不可变、可能为空且可安全共享。没有 processor、脱敏或控制字符变化时，管线复用原记录，避免无意义复制。

## 4. 不可关闭的双安全门

`LogPipeline` 在用户 processor 之前和 terminal sink 之前各执行一次同一安全门。第二次检查用于阻止 processor 重新引入敏感字段；调用方不能关闭默认策略，附加 `SensitiveFieldPolicy` 只能增加限制。

默认拒绝：

- authorization、cookie、token、access/refresh/auth/id token。
- password、passwd、pwd、secret、API/access/secret/private key。
- credential、raw command、command text。
- URI user-info，以及 JDBC、MongoDB、Redis 等连接文本中的凭据属性。

默认脱敏：

- IP、client/source/operator IP、remote/source address。
- operator/operatorId、accountId、playerId、targetId。

默认脱敏器输出固定 `[REDACTED]`。需要跨记录关联时可以显式装配 `HmacSha256LogIdentifierRedactor`；密钥至少 32 字节，输出格式为 `hmac-sha256:keyId:lowercaseHex`，输入按以下域分隔计算：

```text
HMAC(key, "zero-log-identifier-v1" + NUL + domain + NUL + identifier)
```

HMAC 是可选装配，不允许退化为无盐摘要，也不提供完整 IP 明文开关。`LogIdentifierRedactor` 是 sealed 安全边界，只允许固定 `[REDACTED]` 与框架 HMAC-SHA-256 两种实现；HMAC 实现对每次输入无条件重算，合法前缀、当前 keyId 与 64 位十六进制外形都不能证明来源。

双安全门只在当前 `append` 调用栈内保存首次门生成的内部 provenance。终端门仅当消息/字段位置与安全值都完全相同时复用该引用；输入记录或 processor 提供的同外形字符串、新增字段和换位置引用都会重新脱敏。provenance 不写入 `ZeroLogRecord`，也不会暴露给 processor 或 sink。输出格式仍会二次校验，业务无法注入 passthrough redactor 放宽默认脱敏。敏感内容被拒绝时抛出 `ZERO-LOG-SENSITIVE-FIELD-REJECTED`，被拒绝记录不会到达 terminal sink。

附加 `SensitiveFieldPolicy` 会在安全门内看到待判定原值，因此其异常也按不可信输入处理：对外失败不复制原异常 message、cause、suppressed 或可注入的 stack trace 字段，只保留框架生成的安全诊断。该规则不改变 processor/sink 失败按各自契约保留 cause 的行为。

每连接 observer 有序 drain 当前没有独立容量上限、背压或丢弃策略。它解决的是同一连接事件乱序，不证明慢 observer 下的队列容量、长稳或故障降级能力；这些边界仍需后续生产化任务独立冻结。

## 5. 指标 schema 与标签边界

每个指标必须显式声明有序标签 schema：

```java
MetricDefinition operations = new MetricDefinition(
        "zero_game_operations_total",
        "模块操作次数",
        "operations",
        List.of("module", "operation", "result"));

InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
registry.register(operations);
registry.record(new MetricSample(
        operations.name(),
        1.0D,
        Map.of("module", "inventory", "operation", "grant", "result", "success"),
        Instant.now()));
```

运行时边界：

- `MetricDefinition` 使用四参数构造，`labelNames` 有序、不可变、无重复，最多 8 个。
- 同名同定义重复注册幂等；同名不同 description、unit 或标签 schema 以 `METRIC_DEFINITION_CONFLICT` 拒绝。
- `MetricSample.labels()` 必须与定义 schema 完全匹配，不能缺少、多出或改名。
- 全局禁止 `traceId`、`spanId`、player/account/connection/request/room/scene/target/operator/approval 等高基数 ID，也禁止 token、IP/remote address 类标签名。
- 标签值禁止完整 IPv4、IPv6 和带 zone 的 IPv6 字面量。
- `MetricLabelPolicy` 只能收紧全局底线，不能允许框架已禁止的名称或值。
- `PrometheusExporter` 按定义中的标签顺序确定性输出，正确转义 HELP/label，并显式输出 `NaN`、`+Inf`、`-Inf`；非法样本不会被静默跳过。
- `MonitorRuntime.collectOnce()` 返回 `MonitorCollectionResult`。单个系统探针失败通过 `SystemMetricCollectionReport` 显式报告，只保留受控探针枚举和异常类型名，不持有 Throwable 或原异常消息。

`PrometheusExporter` 只生成文本，不启动 HTTP 服务。`MonitorRuntime` 也不创建后台线程；调度必须由 Starter 的统一线程管理提供。

## 6. GM 安全审计归因

GM 审计不再创建平行日志模型。`LoggingGmAuditHook` 通过 `GmAuditRecordFactory` 把安全事件转换为 `ZeroLogRecord`，再调用 `LogAppender` 进入同一双安全门管线。

### 6.1 构造前安全转换

`GmAuditAttributionFactory` 在 `GmAuditEvent` 构造前把原始值转换为固定脱敏或 HMAC 安全引用，域固定为：

- `gm-operator`
- `gm-source`
- `gm-approval`
- `gm-target`

事件只保存安全引用、受控 `GmApprovalState`、target type 和参数名 schema，不保存：

- `GmCommandContext` 或 `GmCommandExecutionRequest`。
- raw command、参数值或原 target。
- 原 operator、来源地址、approvalId。
- roles、permissions、attributes。
- handler 返回正文或原异常 message。

结构指纹只包含 command key、参数名、target type 和参数数量。默认没有部署密钥时，请求关联 fingerprint 为空并从日志字段省略；只有显式 HMAC 策略才短暂读取 raw request 并生成带 keyId 的关联引用。

### 6.2 四态提交语义

| 状态 | 含义 | 典型路径 | 自动重试 |
| --- | --- | --- | --- |
| `NOT_APPLICABLE` | 不涉及正式业务提交 | dry-run | 不适用 |
| `NOT_COMMITTED` | 能证明 handler 未提交 | handler 前失败；明确无副作用 rejection | 可由上层按业务契约决定 |
| `COMMITTED` | handler 已正常成功返回 | 正式执行成功；成功后的审计失败 | 禁止 |
| `UNKNOWN` | handler 已调用但提交结果无法证明 | handler 抛错、返回 null 或非法结果 | 禁止 |

前置审计失败 fail-closed，handler 不会执行。handler 主异常保持主异常，失败审计异常作为 suppressed；后置审计失败通过 `GmAuditFailureException` 暴露 phase 与提交状态，不触发自动重试循环。

GM 的非成功结果必须携带真实 ErrorCode；`ZERO-OK` 不能出现在失败结果或失败审计中。首版新增并冻结的关键错误码包括：

- `ZERO-GM-COMMAND-RESULT-INVALID`
- `ZERO-GM-COMMAND-REJECTED`
- `ZERO-GM-HANDLER-FAILED`
- `ZERO-GM-AUDIT-HOOK-FAILED`

这只是安全审计最小底座，不等于 RBAC、IP 白名单或审批流已经实现。

## 7. 本地示例与可复制片段

先安装当前 SNAPSHOT，再运行无 Docker 示例：

```powershell
mvn -q -DskipTests install
mvn -q -f examples/observability-local/pom.xml test
mvn -q -f examples/observability-local/pom.xml exec:java
```

稳定摘要：

```text
zero-observability-local=ok|logs=5|sensitiveRejected=ZERO-LOG-SENSITIVE-FIELD-REJECTED|labelRejected=ZERO-MONITOR-METRIC-LABEL-FORBIDDEN|stopped=true
```

示例覆盖业务、错误、审计、性能和安全日志，演示 token 字段拒绝与 `traceId` 标签拒绝；它不创建线程、端口、Docker 或外部连接。

`templates/observability-snippet` 提供可复制到业务项目的 `ObservabilityModule`。它不会增加第八种完整玩法模板，也不会修改 `NewLocalGame` 的七模板选择；业务应把 Starter 的 `components.logAppender()` 和 `MetricRegistry` 注入片段，而不是保存 terminal sink。

## 8. opt-in JMH 基准模块

`zero-benchmarks` 是依赖图最上层的叶子模块，只由根 profile 显式启用：

```powershell
mvn -Pbenchmarks -pl :zero-benchmarks -am -DskipTests package
java -jar zero-benchmarks/target/benchmarks.jar
```

- 使用 JMH 1.37。
- 默认 reactor、普通 `mvn test` 和普通 `mvn -Pquality verify` 不包含该模块。
- 当前 observability workload 包含 `LogFieldsBenchmark`、`MetricLabelsBenchmark`、`RedactionBenchmark` 和 `ProductionNetworkObserverBenchmark`。
- benchmark 用于生成可复现的 Java 21 延迟/分配证据，不设置 CI 阈值。
- production network observer 的 current/candidate 只共享外层场景、输入、JDK 和 JMH 参数；current 直写 terminal `LogSink`，candidate 经过真实 `LogPipeline`，内部工作量实质不同，只能方向性观察新增安全语义成本，不能计算回退/改善比例或宣称严格等价。
- 当前结果不能外推为并发吞吐、尾延迟、容量、长稳、SLO、SLA 或生产就绪结论。

任何未来性能阈值、CI 门禁或热路径优化都需要独立确认；文档不手写尚未实际生成的 candidate 数值。

## 9. 生产晋升检查

准备从该最小运行时晋升生产前，至少需要另行完成：

1. 选择并验证生产文件/Kafka sink，明确同步或异步模型、队列容量、批量、背压和失败降级。
2. 提供受鉴权的 Prometheus HTTP endpoint，验证 dashboard、告警路由、去重和静默。
3. 为具体游戏类型执行并发、p95/p99、容量、长稳和故障注入验证，冻结环境、参数和原始结果。
4. 补齐 TraceId 在网络、RPC、Actor、DB、MQ 与异步边界的显式传播契约。
5. 单独推进 GM RBAC、IP 白名单、审批流和后台鉴权，不把本切片的安全归因误认为完整 GM 安全能力。

在这些工作完成之前，当前状态始终是 `productionReady=false`。
