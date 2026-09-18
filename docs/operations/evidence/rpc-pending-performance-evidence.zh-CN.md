# rpc-pending 性能证据口径草案

```text
zero-performance-evidence=track|id=rpc-pending|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `rpc-pending` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”目标，但当前只作为后续 benchmark、Kafka 负载与长稳任务的输入，不是 RPC 延迟报告，也不是生产 pending 容量承诺。

## 1. 定位

`rpc-pending` 覆盖 `KafkaRpcPendingRequests` 的注册、correlationId 查询、完成、失败、容量拒绝、批量失败和关闭，`KafkaRpcTimeoutWheel` 的调度与超时误差，以及 `RpcTransportObserver` / `RpcTransportSnapshot` 的观测开销。组合 workload 可使用内存 Kafka gateway 观察 adapter request/response 与发送失败清理，但不得把它解释为真实 Kafka 延迟。

该 track 必须分三层报告：纯 pending 表、pending + 时间轮、Kafka adapter + in-memory gateway。真实 Kafka broker、多 JVM 和网络成本属于后续独立证据，不能与内存 gateway 数字混报。

当前任务只固定“测什么、怎么记录、哪些边界不能误碰”。它不实例化 pending 表，不启动时间轮线程，不修改 RPC/Kafka 实现，不新增 benchmark profile，不定义阈值。

## 2. 源码证据

| 证据 | 当前状态 | 用途 |
| --- | --- | --- |
| [zero-rpc-common/pom.xml](../../../zero-rpc-common/pom.xml) | 已有 | common 接口与默认调用契约 |
| [zero-rpc/pom.xml](../../../zero-rpc/pom.xml) | 已有 | RPC request/response、transport SPI 和 observer 入口 |
| [zero-rpc-kafka/pom.xml](../../../zero-rpc-kafka/pom.xml) | 已有 | Kafka adapter 和 kafka client 版本入口 |
| [zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcPendingRequests.java](../../../zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcPendingRequests.java) | 已有 | pending map、容量、计数、完成/失败/超时和 observer 入口 |
| [zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcTimeoutWheel.java](../../../zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcTimeoutWheel.java) | 已有 | 单线程 tick、bucket、token 超时调度入口 |
| [zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcAdapter.java](../../../zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcAdapter.java) | 已有 | request/response、oneway、gateway 与 pending 组合入口 |
| [zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcSettings.java](../../../zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcSettings.java) | 已有 | pendingCapacity、pollTimeout、healthCheckInterval 配置入口 |
| [zero-rpc/src/main/java/group/zn/zero/rpc/RpcRequest.java](../../../zero-rpc/src/main/java/group/zn/zero/rpc/RpcRequest.java) | 已有 | correlationId、traceId、timeoutAt 与 payload 模型 |
| [zero-rpc/src/main/java/group/zn/zero/rpc/observer/RpcTransportEvent.java](../../../zero-rpc/src/main/java/group/zn/zero/rpc/observer/RpcTransportEvent.java) | 已有 | transport/pending 事件字段 |
| [zero-rpc/src/main/java/group/zn/zero/rpc/observer/RpcTransportSnapshot.java](../../../zero-rpc/src/main/java/group/zn/zero/rpc/observer/RpcTransportSnapshot.java) | 已有 | pending 和 transport 计数快照 |
| [zero-rpc-kafka/src/test/java/group/zn/zero/rpc/kafka/KafkaRpcPendingRequestsTest.java](../../../zero-rpc-kafka/src/test/java/group/zn/zero/rpc/kafka/KafkaRpcPendingRequestsTest.java) | 已有 | 超时、复用、容量释放与关闭功能证据 |
| [zero-rpc-kafka/src/test/java/group/zn/zero/rpc/kafka/KafkaRpcAdapterTest.java](../../../zero-rpc-kafka/src/test/java/group/zn/zero/rpc/kafka/KafkaRpcAdapterTest.java) | 已有 | adapter、容量、observer、过期请求与失败 gateway 功能证据 |
| [docs/rpc.zh-CN.md](../../../docs/rpc.zh-CN.md) | 已有 | 默认超时、Kafka 不可用、幂等和标准字段边界 |
| [docs/threading-model.zh-CN.md](../../../docs/threading-model.zh-CN.md) | 已有 | 时间轮后台线程和 Actor 不同步等待 RPC 的边界 |
| [docs/operations/performance.zh-CN.md](../../../docs/operations/performance.zh-CN.md) | 已有 | 性能 track 聚合入口 |

这些证据说明功能和观察点已经存在，不说明 pending 容量、时间轮精度、RPC 吞吐或真实 Kafka 性能达标。

## 3. workload 矩阵

| workloadId | 场景 | 隔离目标 | 当前阶段 |
| --- | --- | --- | --- |
| `pending-register-complete-single-thread` | 单线程循环 register + complete | 观察基础 map、permit、future 和 token 成本 | 计划 |
| `pending-register-complete-contended` | 多调用线程使用唯一 correlationId 并发注册/完成 | 观察 `ConcurrentHashMap`、`Semaphore` 与计数器竞争 | 计划 |
| `pending-capacity-rejection` | pending 填满后继续注册 | 观察严格容量拒绝成本、future 失败和 permit 正确性 | 计划 |
| `timeout-wheel-schedule-expire` | 不完成请求，按多个 timeout 批次过期 | 观察 schedule、bucket 扫描、超时误差和容量释放 | 计划 |
| `correlation-id-reuse-stale-task` | 完成后复用 correlationId，等待旧 wheel task 到期 | 观察 token 防误伤和 stale task 成本 | 计划 |
| `fail-all-unavailable` | 填充 pending 后模拟 transport 关闭或不可用 | 观察批量删除、future 失败、observer 和容量清理 | 计划 |
| `observer-event-overhead` | 分别使用 noop 与 recording observer | 隔离 event 构造和 observer callback 成本 | 计划 |
| `request-response-inmemory-gateway` | Kafka adapter 通过内存 gateway 完成 request/response | 观察 adapter、envelope、pending、handler 和 response 组合成本 | 计划 |
| `kafka-send-failure-cleanup` | gateway 立即返回失败 future | 观察发送失败后 pending 清理、ErrorCode 与 observer 成本 | 计划 |

每个 workload 必须至少覆盖 64 B、1 KiB、16 KiB payload，以及 pending capacity 1、64、1024、16384。大容量样本必须结合机器内存记录，不得以 OOM 或 swap 后数字作为正常结果。

## 4. 环境元数据

后续正式结果至少记录以下字段：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `rpc-pending-20260710-local-win` |
| `track` | 性能 track | `rpc-pending` |
| `javaVersion` | Java 版本 | `21.0.x` |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g` |
| `kafkaClientVersion` | Maven 解析的 Kafka client 版本 | dependency tree 实际值 |
| `pendingCapacity` | pending 最大容量 | `64` / `1024` / `16384` |
| `concurrentCallers` | 并发注册/完成线程数 | `1` / `4` / `16` |
| `tickDurationMillis` | 时间轮 tick | `10` |
| `wheelSize` | 时间轮桶数 | `512` |
| `timeoutMillis` | 请求 timeout | `30` / `300` / `3000` |
| `completionRatio` | 完成/超时/失败比例 | `80/10/10` |
| `observerMode` | observer 模式 | `noop` / `recording` / `production-adapter` |
| `payloadBytes` | RPC payload 字节数 | `64` / `1024` / `16384` |
| `warmupRounds` | 预热轮数 | `5` |
| `measureRounds` | 测量轮数 | `10` |
| `thresholdMode` | 阈值模式 | `none` |

`thresholdMode=none` 表示第一阶段只记录可复现结果，不设置通过/失败阈值。

## 5. 测量指标

| 指标 | 说明 |
| --- | --- |
| `registerLatencyNs` | pending register 延迟，至少记录 avg/p50/p95/p99/max |
| `completeLatencyNs` | response correlationId 查找、删除、permit 释放和 future 完成延迟 |
| `correlationLookupLatencyNs` | 独立观察 complete/fail 查找路径时的延迟 |
| `timeoutOvershootMillis` | 实际完成超时时刻减 `timeoutAt`，必须结合 tick 解释 |
| `operationsPerSecond` | 指定 workload 的完成操作数/秒 |
| `pendingSizeHighWatermark` | 测量窗口内 pending 最高水位 |
| `rejectedRequestCount` | 容量、重复 correlationId、关闭或过期导致的拒绝数 |
| `timedOutRequestCount` | 时间轮实际超时完成数 |
| `staleTimeoutIgnoredCount` | 旧 token/wheel task 被幂等忽略的样本数；注明采集方式 |
| `failAllDrainTimeMillis` | failAll/close 清空 N 个 pending 的耗时 |
| `observerCallbackLatencyNs` | observer callback 延迟，noop 与 recording 分开报告 |
| `allocationBytesPerOp` | 每次 register/complete 或组合调用分配量 |
| `leakedPendingCount` | workload 结束后未完成、未失败或未超时的 pending 数，必须为 0 |

吞吐与延迟必须同时报告。超时误差不能只报平均值；至少给出 p95/p99/max。`leakedPendingCount` 非 0 时整组样本无效，不能只把它记为普通 warning。

## 6. 结果格式

每条结果至少包含以下字段：

```text
evidenceId | workloadId | pendingCapacity | concurrentCallers
tickDurationMillis | wheelSize | timeoutMillis | completionRatio
observerMode | payloadBytes | sampleCount | operationsPerSecond
registerAvgNs | registerP95Ns | registerP99Ns
completeAvgNs | completeP95Ns | completeP99Ns
timeoutOvershootP95Millis | timeoutOvershootP99Millis | timeoutOvershootMaxMillis
pendingSizeHighWatermark | rejectedRequestCount | timedOutRequestCount
staleTimeoutIgnoredCount | failAllDrainTimeMillis | observerCallbackLatencyNs
allocationBytesPerOp | leakedPendingCount | thresholdMode | notes
```

首轮报告必须保留原始命令、Maven 解析版本、JVM 参数、机器信息、warmup 和每轮样本。纯 pending、pending + wheel、adapter + in-memory gateway 必须分表，真实 Kafka 结果也必须另表并记录 broker、partition、acks、网络和 JVM 环境。

## 7. 风险边界

以下动作必须单独建档并等待用户确认：

- 新增 JMH、benchmark Maven profile、真实 Kafka 压测、多 JVM 长稳或 CI 门禁。
- 修改 request/response、oneway、broadcast、默认 3 秒超时或 `timeoutAt` 拒绝语义。
- 修改 pending 数据结构、容量、Semaphore、公平性、时间轮 tick/wheel size 或后台线程。
- 修改 Kafka 不可用、发送失败、超时、取消、fail-all 或 consumer 恢复策略。
- 修改 observer、snapshot、ErrorCode、重试、幂等或去重边界。
- 定义 pending 数量、超时误差、吞吐、延迟或生产容量阈值。
- 根据结果修改 RPC/Kafka 热路径。

本 readiness 不新增 JMH，不定义性能阈值，不修改 RPC 模式、默认超时或 `timeoutAt` 语义，不修改 pending 容量、时间轮或后台线程，不修改 Kafka 不可用和发送失败策略，不修改重试或幂等语义，也不作为生产容量承诺。

## 8. 后续推进

```text
检查本页负载矩阵与采集口径
  -> 用户确认 benchmark/profile/采集边界
  -> 独立 benchmark 任务建档
  -> 纯 pending register/complete/capacity 基线
  -> pending + timeout wheel 基线
  -> adapter + in-memory gateway 组合基线
  -> 用户确认真实 Kafka / 多 JVM 环境
  -> Kafka 负载和长稳证据
  -> 结果审阅
  -> 如需语义或热路径调整，再次高风险确认
```

当前只完成 readiness。正式 benchmark、真实 Kafka 证据、阈值、CI 门禁、容量结论和实现优化均未完成。
