# repository-save 性能证据口径草案

```text
zero-performance-evidence=track|id=repository-save|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `repository-save` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”目标，但当前只作为后续 benchmark、真实 Adapter 负载与长稳任务的输入，不是数据库性能报告，也不是生产存储容量承诺。

## 1. 定位

`repository-save` 覆盖 `ZeroDataEnvelopeCodec`、`ZeroDataEnvelopeCrudRepository` 的 create/update/version conflict/saveAll，以及 `DefaultPersistenceManager` 的 dirty 登记、绑定域快照、flush、成功清理和失败保留。Adapter 对比只使用 Mongo/Redis/PostgreSQL 内存 Store 作为形态观察；真实 Driver、网络、连接池、事务、Lua、SQL 与磁盘成本必须另行测量。

该 track 必须分四层报告：envelope codec、Repository + in-memory Store、PersistenceManager + fake target、真实 Driver Adapter。内存 Store 数字不得解释为 MongoDB、Redis 或 PostgreSQL 生产性能。

当前任务只固定“测什么、怎么记录、哪些边界不能误碰”。它不创建 Store 或 PersistenceManager，不启动 scheduler，不连接数据库，不修改数据实现，不新增 benchmark profile，不定义阈值。

## 2. 源码证据

| 证据 | 当前状态 | 用途 |
| --- | --- | --- |
| [zero-data/pom.xml](../../../zero-data/pom.xml) | 已有 | Repository、envelope、mapping 和 persistence 基础入口 |
| [zero-data-mongo/pom.xml](../../../zero-data-mongo/pom.xml) | 已有 | MongoDB Driver Adapter 入口 |
| [zero-data-redis/pom.xml](../../../zero-data-redis/pom.xml) | 已有 | Redis snapshot/index/journal 与 Driver Adapter 入口 |
| [zero-data-postgresql/pom.xml](../../../zero-data-postgresql/pom.xml) | 已有 | PostgreSQL Driver Adapter 入口 |
| [zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelope.java](../../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelope.java) | 已有 | 统一存储信封字段与 payload 防御性复制 |
| [zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeCodec.java](../../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeCodec.java) | 已有 | envelope magic/version 与 zcode 编解码入口 |
| [zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeStore.java](../../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeStore.java) | 已有 | Store save/saveIfVersion 公共边界 |
| [zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeCrudRepository.java](../../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeCrudRepository.java) | 已有 | Repository 版本递增、CAS、batch 和 ErrorCode 编排 |
| [zero-data/src/main/java/group/zn/zero/data/persistence/DefaultPersistenceManager.java](../../../zero-data/src/main/java/group/zn/zero/data/persistence/DefaultPersistenceManager.java) | 已有 | dirty、绑定快照、flush 与失败保留入口 |
| [zero-data-mongo/src/main/java/group/zn/zero/data/mongo/MongoDataEnvelopeStore.java](../../../zero-data-mongo/src/main/java/group/zn/zero/data/mongo/MongoDataEnvelopeStore.java) | 已有 | Mongo document 内存形态参考 |
| [zero-data-redis/src/main/java/group/zn/zero/data/redis/RedisDataEnvelopeStore.java](../../../zero-data-redis/src/main/java/group/zn/zero/data/redis/RedisDataEnvelopeStore.java) | 已有 | Redis snapshot/index/journal 内存形态参考 |
| [zero-data-postgresql/src/main/java/group/zn/zero/data/postgresql/PostgresqlDataEnvelopeStore.java](../../../zero-data-postgresql/src/main/java/group/zn/zero/data/postgresql/PostgresqlDataEnvelopeStore.java) | 已有 | PostgreSQL row 内存形态参考 |
| `ZeroDataEnvelopeCodecTest` / `ZeroDataEnvelopeCrudRepositoryTest` | 已有 | envelope roundtrip、Repository CAS 和版本冲突功能证据 |
| `DefaultPersistenceManagerTest` | 已有 | dirty flush、线程绑定与失败保留功能证据 |
| 三类 `*DataEnvelopeStoreTest` | 已有 | Mongo/Redis/PostgreSQL 内存 Store CAS 功能证据 |
| [docs/data-cache.zh-CN.md](../../../docs/data-cache.zh-CN.md) | 已有 | 数据职责、在线数据、dirty/flush 与 Adapter 边界 |
| [docs/operations/api-compatibility-gate.zh-CN.md](../../../docs/operations/api-compatibility-gate.zh-CN.md) | 已有 | Repository、mapping、envelope 和 persistence limited 契约 |
| [docs/operations/performance.zh-CN.md](../../../docs/operations/performance.zh-CN.md) | 已有 | 性能 track 聚合入口 |

这些证据说明统一格式、CAS、flush 和失败保留观察点已经存在，不说明生产数据库吞吐、延迟、一致性或容量达标。

## 3. workload 矩阵

| workloadId | 场景 | 隔离目标 | 当前阶段 |
| --- | --- | --- | --- |
| `envelope-encode-decode` | 固定 metadata 和多档 payload 编解码 | 观察 envelope header、payload copy 和 zcode 成本 | 计划 |
| `repository-save-create` | version=0 的新实体 prepare/encode/saveIfVersion | 观察创建、ID 编码、版本递增和 Store CAS 成本 | 计划 |
| `repository-save-if-version-update` | 连续按正确 expectedVersion 更新 | 观察更新 CAS 和 payload 编码成本 | 计划 |
| `repository-version-conflict` | 使用 stale version 保存同一实体 | 观察冲突检测、异常 future 和 ErrorCode 成本 | 计划 |
| `repository-save-all-batch` | 以 1/16/128/1024 实体调用 saveAll | 观察当前串行 batch 编排和对象分配 | 计划 |
| `persistence-flush-dirty-batch` | 登记 N 个 dirty target 后手工 flush | 观察快照捕获、保存、成功清理与统计成本 | 计划 |
| `persistence-failure-retain-dirty` | fake Repository 按比例失败 | 观察失败传播、dirty 保留、失败计数和后续重试输入 | 计划 |
| `adapter-inmemory-cas-comparison` | Mongo/Redis/PostgreSQL 内存 Store 使用相同 envelope/CAS workload | 观察不同形态的本地编排差异，不代表真实 Driver | 计划 |
| `payload-size-scaling` | 64 B、1 KiB、16 KiB、256 KiB payload | 观察 copy、encode、allocation 和延迟随 payload 增长 | 计划 |

每个 Repository workload 必须至少覆盖实体数 1、64、1024，batch size 1、16、128；Persistence workload 必须同时记录 dirty 总数和失败比例。真实 Adapter workload 必须使用隔离测试数据和显式清理方案。

## 4. 环境元数据

后续正式结果至少记录以下字段：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `repository-save-20260710-local-win` |
| `track` | 性能 track | `repository-save` |
| `javaVersion` | Java 版本 | `21.0.x` |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g` |
| `adapterMode` | 测量层级 | `envelope-only` / `inmemory` / `mongo-driver` / `redis-driver` / `postgresql-driver` |
| `entityCount` | 本轮实体数 | `1` / `64` / `1024` |
| `payloadBytes` | 业务 payload 字节数 | `64` / `1024` / `16384` / `262144` |
| `batchSize` | saveAll/flush batch | `1` / `16` / `128` |
| `dirtyCount` | flush 前 dirty 数量 | `1024` |
| `expectedVersion` | CAS 期望版本 | `0` / `1` / `100` |
| `conflictRatio` | 版本冲突比例 | `0` / `0.1` / `0.5` |
| `flushConcurrency` | 同时 flush 调用数 | `1`；其他值需单独说明语义 |
| `snapshotBindingMode` | 快照捕获执行域 | `direct-test` / `managed-executor` |
| `warmupRounds` | 预热轮数 | `5` |
| `measureRounds` | 测量轮数 | `10` |
| `thresholdMode` | 阈值模式 | `none` |

真实 Driver 结果还必须记录驱动版本、数据库版本、Docker/远程地址来源、连接池、write concern/事务、Redis 持久化、PostgreSQL fsync 与清理方式；敏感连接信息不得进入报告。

## 5. 测量指标

| 指标 | 说明 |
| --- | --- |
| `envelopeEncodeLatencyNs` | envelope encode 延迟 |
| `envelopeDecodeLatencyNs` | envelope decode 延迟 |
| `saveLatencyNs` | Repository save 完成延迟，至少 avg/p50/p95/p99/max |
| `saveThroughputPerSecond` | 完成保存实体数/秒 |
| `saveIfVersionLatencyNs` | Store CAS 调用延迟 |
| `versionConflictCount` | 版本条件不满足或 Repository conflict 数 |
| `flushLatencyNs` | 一轮 dirty flush 完成延迟 |
| `flushBacklogDepth` | flush 前后 dirty/backlog 数量 |
| `dirtyRetainedCount` | 失败后仍保留的 dirty 数量 |
| `failedFlushCount` | flush 失败目标或轮次计数 |
| `allocationBytesPerOp` | 每次 encode/save/flush 分配量 |
| `payloadEncodedBytes` | envelope 编码后实际字节数 |
| `leakedDirtyCount` | workload 结束后既未成功清理、又未按失败策略保留的异常目标数，必须为 0 |

保存吞吐必须与延迟、payload 和 batch 同时报告。失败 workload 必须验证 `dirtyRetainedCount` 与预期一致；不能为了得到更好吞吐而吞掉错误或提前清理 dirty。

## 6. 结果格式

每条结果至少包含以下字段：

```text
evidenceId | workloadId | adapterMode | entityCount | payloadBytes
batchSize | dirtyCount | expectedVersion | conflictRatio
flushConcurrency | snapshotBindingMode | sampleCount
saveThroughputPerSecond | saveAvgNs | saveP95Ns | saveP99Ns
envelopeEncodeAvgNs | envelopeDecodeAvgNs | saveIfVersionAvgNs
flushAvgNs | flushP95Ns | flushBacklogDepth
versionConflictCount | dirtyRetainedCount | failedFlushCount
allocationBytesPerOp | payloadEncodedBytes | leakedDirtyCount
thresholdMode | notes
```

首轮报告必须保留原始命令、Maven 解析版本、JVM 参数、机器信息、warmup 和每轮样本。envelope、内存 Repository、PersistenceManager、三类 Driver Adapter 必须分表。真实数据库结果必须同时保留容器/服务版本与配置摘要。

## 7. 风险边界

以下动作必须单独建档并等待用户确认：

- 新增 JMH、benchmark Maven profile、真实数据库压测、Docker external-tests、故障注入、长稳或 CI 门禁。
- 修改 envelope、magic/version、mapping annotation、schema/codec version、key、journal、SQL、Lua 或存储格式。
- 修改 Repository/Store API、CAS、expectedVersion、版本冲突或 ErrorCode 语义。
- 修改 PersistenceManager、dirty 粒度、线程绑定、flush、成功清理、失败保留、重试或降级。
- 修改 Mongo/Redis/PostgreSQL Driver、连接池、事务、write concern 或持久化策略。
- 定义保存延迟、batch、flush backlog、数据库吞吐或生产容量阈值。
- 根据结果修改数据/Adapter 热路径。

本 readiness 不新增 JMH，不定义性能阈值，不修改 envelope、mapping、key、journal 或存储格式，不修改 Repository、CAS 或版本冲突语义，不修改 dirty、flush、成功清理或失败保留，不连接 MongoDB、Redis 或 PostgreSQL，也不作为生产容量承诺。

## 8. 后续推进

```text
检查本页负载矩阵与采集口径
  -> 用户确认 benchmark/profile/采集边界
  -> 独立 benchmark 任务建档
  -> envelope + in-memory Repository/CAS 基线
  -> PersistenceManager dirty/flush/failure 基线
  -> 用户确认 Docker 组件、隔离数据和清理方式
  -> Mongo/Redis/PostgreSQL Driver 分层证据
  -> 故障注入和长稳证据
  -> 结果审阅
  -> 如需格式、CAS、flush 或热路径调整，再次高风险确认
```

当前只完成 readiness。正式 benchmark、真实数据库证据、阈值、CI 门禁、容量结论和实现优化均未完成。
