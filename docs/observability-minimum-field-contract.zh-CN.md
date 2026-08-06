# zeroServer 生产可观测性最小字段契约与最小实现

本文记录 `observability-minimum-field-runtime` 的首版契约与当前最小运行时实现事实。

状态：`minimum-slice-implemented / productionReady=false`

当前边界包括：原位收敛公共模型、`LogAppender / LogSink` 类型隔离、GM 构造前安全归因、非成功结果真实 ErrorCode、四态 `GmBusinessCommitState`、仅由 `-Pbenchmarks` 启用的 `zero-benchmarks` 与 JMH 1.37，以及默认不设置 CI 性能阈值。

## 1. 已实现目标

- 统一 `ZeroLogRecord` 首版固定字段和字段预算。
- 业务、observer、GM、示例与脚手架统一通过 `LogAppender` 写日志。
- terminal `LogSink` 只保留在顶层装配，始终由 `LogPipeline` 包装。
- processor 前后执行不可关闭的敏感内容安全门。
- 指标定义显式携带有序标签 schema，样本必须完全匹配。
- 全局阻止高基数指标标签和完整 IP 字面量。
- GM 事件构造前完成安全 attribution，不保存原上下文或 raw 值。
- GM 失败使用真实 ErrorCode 与四态提交语义，禁止对未知或已提交结果自动重试。
- 提供无 Docker 本地正反路径示例、可复制片段和 opt-in JMH 叶子模块。

## 2. 非目标与剩余边界

- 不实现生产文件/Kafka sink、批量、背压、落地重试或降级。
- 不实现 Prometheus HTTP endpoint、完整 Grafana 部署或远程告警闭环。
- 不承诺 TraceId 自动贯穿 RPC、DB、MQ、Actor、异步回调或虚拟线程。
- 不实现 OpenTelemetry。
- 不实现 GM REST/RPC、RBAC、IP 白名单、完整审批流或后台鉴权。
- 不定义 CI 性能阈值、SLO 或 SLA。
- 不证明并发吞吐、p95/p99、容量、长稳或生产可用。

因此本切片完成不等于 observability production standard 完成，也不等于 zeroServer 长期目标完成。

## 3. 固定日志字段契约

`ZeroLogRecord.SCHEMA_VERSION` 固定为 `"1"`。首版字段顺序为：

| 字段 | 必需性 | 契约 |
| --- | --- | --- |
| `schemaVersion` | 必填 | 固定 `"1"` |
| `time` | 必填 | `Instant` |
| `level` | 必填 | `LogLevel` |
| `logType` | 必填 | 七类 `LogType` |
| `serviceName` | 必填 | 来自 `LogSource` |
| `instanceId` | 必填 | 来自 `LogSource` |
| `module` | 必填 | 来自 `LogSource` |
| `operation` | 必填 | 来自 `LogOperation` |
| `result` | 必填 | `LogResult` |
| `traceId` | 必填 | 调用方显式传入 |
| `errorCode` | 条件必填 | 失败轴必须有，开始/成功禁止有 |
| `message` | 必填 | 有界安全摘要 |
| `fields` | 必填 | 有序、不可变、可能为空 |

日志分类通过 `logType()` 读取，扩展数据统一使用 `fields()`，审计复用同一 `ZeroLogRecord`。`module` 是固定字段，通过 `LogSource` 统一提供。

字段预算：最多 32 个扩展字段；字段名 64 字符；单字段值 4096 字符；message 4096 字符；message 与字段名值总计 16384 字符；固定标识 128 字符。

ErrorCode 判定式：

```text
requiresErrorCode = level == ERROR
                 OR logType == ERROR
                 OR result IN {FAILURE, REJECTED, TIMEOUT, DEGRADED}
```

`STARTED / SUCCESS` 禁止 ErrorCode，并禁止与 `ERROR` level/type 组合。

## 4. 写入与安全契约

```text
business / observer / GM
  -> LogAppender
  -> LogPipeline
       -> validate
       -> baseline safety gate
       -> ordered processors + validate
       -> terminal safety gate
  -> LogSink
```

- `LogAppender` 是唯一业务安全写入端口。
- `LogSink` 是不带安全保证的 terminal SPI，只能在装配层使用。
- Starter 创建并复用唯一 `LogPipeline`，`ZeroRuntimeComponents` 只暴露 `logAppender()`。
- 默认策略不可关闭，附加 `SensitiveFieldPolicy` 只能收紧。
- 默认拒绝 token、password、secret、credential、raw command、凭据 URI/连接文本。
- 默认脱敏 IP、operator、accountId、playerId、targetId。
- 默认输出 `[REDACTED]`；可选 HMAC-SHA-256 输出带 keyId，并对字段域做分隔。`LogIdentifierRedactor` 只允许这两种框架安全实现，不接受 passthrough、自定义无盐摘要或其他可放宽默认脱敏的函数。
- HMAC redactor 对每次输入无条件重算；仅有 `hmac-sha256:keyId:64hex` 外形不能证明引用由当前密钥生成。
- 终端门只复用同一次 `append` 首次门在相同消息/字段位置生成的完全相同安全值。该“位置 + 值” provenance 只存在于管线调用栈，不进入公开记录；输入或 processor 伪造、新增、换位置的同外形值必须重新脱敏。
- 附加 `SensitiveFieldPolicy` 异常不得把原 message、cause、suppressed 或不可信 stack trace 字段带入对外异常；processor/sink cause 的独立保留契约不变。
- 控制字符统一转义，processor 不能通过第二道安全门重新引入敏感数据。

production network observer 事件由每连接有序 drain 提交到共享受管 executor；同连接保持提交顺序、跨连接允许并发。该队列尚未冻结容量、背压或丢弃策略，因此只构成顺序正确性证据，不构成容量或长稳证明。

## 5. 指标字段契约

- `MetricDefinition(name, description, unit, labelNames)` 必须显式提供有序 schema。
- 标签 schema 不可变、无重复，最多 8 个。
- 同名同定义注册幂等；同名不同定义拒绝。
- 样本 label key 必须与定义完全匹配。
- 全局禁止 TraceId、SpanId、玩家/账号/连接/请求/房间/场景/目标/操作者/审批 ID、token、IP/remote address 类 label。
- label value 禁止完整 IPv4/IPv6 字面量。
- `MetricLabelPolicy` 只能收紧。
- exporter 按定义顺序输出标签，显式处理 `NaN / +Inf / -Inf`，非法样本不能静默跳过。
- 单个系统探针失败必须进入 `SystemMetricCollectionReport`，不得生吞；报告不持有 Throwable 或原异常 message。

TraceId 进入日志字段，不进入默认 Prometheus label。本切片不承诺全仓自动 Trace 传播。

## 6. GM 审计契约

### 6.1 构造前归因

原始 operator、来源地址、approvalId 和 target 在 `GmAuditEvent` 构造前转换为安全引用，域固定为：

```text
gm-operator
gm-source
gm-approval
gm-target
```

事件不持有 context、execution request、raw command、参数值、原 target/operator/source/approval、roles、permissions、attributes 或原异常 message。

结构 fingerprint 只包含 schema 元数据；默认无密钥时完整请求 fingerprint 为空并从日志字段省略。需要请求关联时必须显式使用带部署密钥和 keyId 的 HMAC，不能使用无盐摘要。

### 6.2 四态提交语义

| 状态 | 已实现语义 |
| --- | --- |
| `NOT_APPLICABLE` | dry-run，不涉及业务提交 |
| `NOT_COMMITTED` | handler 前失败，或 handler 明确返回无副作用 rejection |
| `COMMITTED` | handler 正常成功返回；成功后的后置审计失败仍保持已提交 |
| `UNKNOWN` | handler 抛错、返回 null 或非法结果，无法证明是否提交 |

`COMMITTED / UNKNOWN` 禁止自动重试。前置审计失败 fail-closed；handler 主异常保持主异常，失败审计异常作为 suppressed。

失败结果不能使用成功码。当前关键真实 ErrorCode：

| 枚举 | category | code | message |
| --- | --- | --- | --- |
| `COMMAND_RESULT_INVALID` | `SYSTEM` | `ZERO-GM-COMMAND-RESULT-INVALID` | `gm command result is invalid` |
| `COMMAND_REJECTED` | `CLIENT_REQUEST` | `ZERO-GM-COMMAND-REJECTED` | `gm command was rejected` |
| `HANDLER_FAILED` | `SYSTEM` | `ZERO-GM-HANDLER-FAILED` | `gm command handler failed` |
| `AUDIT_HOOK_FAILED` | `SYSTEM` | `ZERO-GM-AUDIT-HOOK-FAILED` | `gm audit hook failed` |

## 7. 本地示例与模板契约

`examples/observability-local` 覆盖五类日志、敏感字段拒绝、指标高基数标签拒绝和无残留停止：

```text
zero-observability-local=ok|logs=5|sensitiveRejected=ZERO-LOG-SENSITIVE-FIELD-REJECTED|labelRejected=ZERO-MONITOR-METRIC-LABEL-FORBIDDEN|stopped=true
```

`templates/observability-snippet` 是可叠加到业务工程的接入片段。它只保存 `LogAppender` 与 `MetricRegistry`，不直接接触 terminal `LogSink`；它不改变七种完整玩法模板清单。

## 8. Benchmark 契约

- `zero-benchmarks` 只由根 `-Pbenchmarks` profile 加入 reactor。
- 模块使用 JMH 1.37，是运行时模块依赖图的最上层叶子；任何运行时模块不得反向依赖。
- 默认 `mvn test` 与默认 `mvn -Pquality verify` 不发现、编译或运行 JMH。
- 当前四组 workload 为日志字段、指标标签、脱敏 MISS/HIT 和 production network observer。
- benchmark 只生成方向性延迟/分配证据，不设置 CI 阈值。
- production network observer 的 current/candidate 只共享外层场景、输入、JDK 和 JMH 参数；current 直写 terminal `LogSink`，candidate 经过真实 `LogPipeline`，内部工作量实质不同，只能方向性观察新增安全语义成本，不能计算回退/改善比例。
- 不把尚未实际生成的 candidate 数字写入契约，也不从单线程内存 workload 外推生产容量。

## 9. 实施后的后续顺序

```text
observability minimum slice
  -> root test / quality / example verification
  -> GitHub Design Proposal for production sink / HTTP endpoint / Trace 传播
  -> 容量、长稳和故障验证
  -> 才能重新评估 productionReady
```

后续扩展必须复用当前 `LogAppender`、统一 `ZeroLogRecord`、GM 安全归因、真实 ErrorCode 和四态提交语义；若要改变这些公共契约，应提供兼容与迁移方案。
