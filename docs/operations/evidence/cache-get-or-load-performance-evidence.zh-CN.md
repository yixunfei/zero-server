# cache-get-or-load 性能证据口径草案

```text
zero-performance-evidence=track|id=cache-get-or-load|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `cache-get-or-load` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”和开箱即用缓存目标，但当前只作为后续 benchmark、真实 Redis 负载与长稳任务的输入，不是缓存性能报告，也不是生产命中率或容量承诺。

## 1. 定位

`cache-get-or-load` 覆盖 `InMemoryCacheService` 的本地命中、loader、per-key singleflight 和负缓存，`LayeredCacheService` 的 L1 -> L2 -> loader -> L2/L1 回填，以及 `CacheLoadCoordinator` 的不同 key 并发上限与拒绝路径。Redis 对比必须区分无 Store 本地模式、fake L2 和真实 `RedisCacheStore` Driver。

该 track 必须分五层报告：L1 only、Layered + fake L2、isolated coordinator、Redis distributed service without Driver、real Redis Driver。L1 或 fake L2 数字不得解释为 Redis 生产性能。

当前 `CacheStatistics` 只能提供聚合 hit/miss/load 计数，不能单独证明 L1 hit、L2 hit 或 singleflight waiter 数。正式 benchmark 必须在 harness 侧记录 L1/L2 Store 调用、loader 调用和 waiter 合并数，或者先单独确认低开销观测扩展。

`CachePolicy.allowStaleOnBackendFailure` 当前只是策略字段，本证据不把它记录为已实现 stale-read 行为。L2 失败后的 workload 只验证当前可观察到的 degraded 标记、loader 回源、L1 回填和 best-effort L2 写回。

当前任务只固定“测什么、怎么记录、哪些边界不能误碰”。它不创建 Cache，不调用 loader，不启动线程，不连接 Redis，不修改缓存实现，不新增 benchmark profile，不定义阈值。

## 2. 源码证据

| 证据 | 当前状态 | 用途 |
| --- | --- | --- |
| [zero-cache/pom.xml](../../../zero-cache/pom.xml) | 已有 | 缓存抽象与本地实现模块入口 |
| [zero-data-redis/pom.xml](../../../zero-data-redis/pom.xml) | 已有 | Redis L2 Adapter 与 Driver 入口 |
| `CacheService` / `CacheLoader` | 已有 | get/put/invalidate 与异步 loader 基础契约 |
| `CachePolicy` | 已有 | TTL、负缓存、jitter、L1 容量和并发加载上限 |
| `CacheEntry` / `CacheStoreEntry` | 已有 | L1/L2 value、版本、过期和 negative 标记 |
| `CacheStore` | 已有 | L2 get、版本写入与条件失效 SPI |
| `InMemoryCacheService` | 已有 | L1 hit、loader、singleflight、negative 和近似淘汰入口 |
| `LayeredCacheService` | 已有 | L1/L2/loader 编排、回填、best-effort 写回和降级入口 |
| `CacheLoadCoordinator` | 已有 | per-key singleflight、pending 与最大并发拒绝入口 |
| `CacheStatistics` / `CacheHealthSnapshot` | 已有 | 聚合统计和后端失败、写回失败、backlog 观测前置 |
| `RedisDistributedCacheService` | 已有 | Layered cache 的 Redis Adapter 组合入口 |
| `RedisCacheStore` | 已有 | Redis Driver get、Lua 版本写入/失效与 key namespace |
| `RedisCacheEnvelope` / `RedisCacheEnvelopeCodec` | 已有 | Redis L2 持久字节格式与 value codec 包装 |
| `InMemoryCacheServiceTest` | 已有 | 本地 load-once、版本和负缓存功能证据 |
| `LayeredCacheServiceTest` | 已有 | L2 回填、loader、负缓存与 L2 失败降级功能证据 |
| `CacheScenarioExampleTest` | 已有 | 玩家资料、热点场景、版本失效和 Redis 降级场景证据 |
| `RedisDistributedCacheServiceTest` | 已有 | 无 Redis Store 的本地退化与健康计数证据 |
| `RedisCacheStoreExternalIT` | 已有 | 真实 Redis get/put/version/invalidate 功能入口；本任务不运行 |
| [docs/data-cache.zh-CN.md](../../../docs/data-cache.zh-CN.md) | 已有 | 缓存职责、L1/L2、击穿/穿透/雪崩和降级边界 |
| [docs/operations/api-compatibility-gate.zh-CN.md](../../../docs/operations/api-compatibility-gate.zh-CN.md) | 已有 | Cache API、Redis Adapter 与 limited 契约审阅 |
| [docs/operations/performance.zh-CN.md](../../../docs/operations/performance.zh-CN.md) | 已有 | 性能 track 聚合入口 |

这些证据说明功能入口和观测点已经存在，不说明并发吞吐、尾延迟、Redis RTT、命中率或容量达标。

## 3. workload 矩阵

| workloadId | 场景 | 隔离目标 | 当前阶段 |
| --- | --- | --- | --- |
| `inmemory-l1-hit` | 预填 value 后重复 getOrLoad 同一组 key | 观察有效 L1 entry、Optional 和统计成本 | 计划 |
| `inmemory-loader-miss` | 每个 key 首次 miss 并由同步/已完成 future loader 返回 value | 观察 miss、loader、entry 构造和 L1 回填成本 | 计划 |
| `inmemory-singleflight-hot-key` | 多调用方并发等待同一未完成 loader future | 观察热点合并、waiter 完成和 loading 清理成本 | 计划 |
| `inmemory-negative-cache-hit` | loader 返回 empty，后续重复访问相同缺失 key | 观察负缓存防穿透、Optional empty 和 negative TTL 路径 | 计划 |
| `layered-l2-hit-backfill` | L1 miss、fake L2 hit，完成 L1 回填 | 观察 L2 future、过期判断、entry 转换和回填成本 | 计划 |
| `layered-l1-after-backfill` | 完成一次 L2 回填后重复访问 | 隔离分层服务的 L1 热命中成本 | 计划 |
| `layered-loader-writeback` | L1/L2 miss 后 loader 返回 value/empty 并 best-effort 写 L2 | 观察 loader、正/负 entry、L2 写回与 L1 回填成本 | 计划 |
| `layered-backend-failure-degrade` | fake L2 按比例读写失败，loader 仍返回结果 | 观察异常 future、degraded/失败计数和本地可用路径成本 | 计划 |
| `coordinator-distinct-key-backpressure` | 超过 maxConcurrentLoads 的不同 key 保持未完成 | 观察 pending、拒绝、ErrorCode 和完成清理成本 | 计划 |
| `redis-driver-get-or-load` | 真实 Redis L2 hit/miss/negative/writeback | 观察 codec、Lua、网络、连接和 Redis 服务成本；需另行确认 | 计划 |

每个本地 workload 至少覆盖 key cardinality 1、64、4096，并发 1、8、64、256，value 64 B、1 KiB、16 KiB、256 KiB。热点 workload 必须同时覆盖 hotKeyRatio 0、0.9、1.0；负缓存 workload 必须记录 loader 调用是否被后续访问阻断。

singleflight workload 必须让 loader future 保持可控未完成状态，确认多个 waiter 真正重叠；不能使用立即完成 future 后把串行命中误报为并发合并。背压 workload 必须使用不同 key，否则相同 key 会被 singleflight 合并而无法触发并发上限。

## 4. 环境元数据

后续正式结果至少记录以下字段：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `cache-get-or-load-20260710-local-win` |
| `track` | 性能 track | `cache-get-or-load` |
| `javaVersion` | Java 版本 | `21.0.x` |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g` |
| `cacheMode` | 测量层级 | `l1-only` / `fake-l2` / `coordinator` / `redis-driver` |
| `keyCardinality` | key 数量 | `1` / `64` / `4096` |
| `operationCount` | 每轮操作数 | `1000000` |
| `concurrency` | 同时调用方数量 | `1` / `8` / `64` / `256` |
| `hotKeyRatio` | 请求落到热点 key 的比例 | `0` / `0.9` / `1.0` |
| `valueBytes` | value 编码前字节数 | `64` / `1024` / `16384` / `262144` |
| `targetHitRatio` | workload 目标命中比例 | `0` / `0.5` / `0.95` / `1.0` |
| `negativeRatio` | loader 返回 empty 比例 | `0` / `0.1` / `1.0` |
| `loaderLatencyMillis` | 可控 loader 延迟 | `0` / `1` / `10` / `100` |
| `l2LatencyMillis` | fake L2 或测得 Redis RTT | `0` / `1` / `5` |
| `l2FailureRatio` | L2 读写失败比例 | `0` / `0.01` / `1.0` |
| `maxConcurrentLoads` | CachePolicy 并发加载上限 | `32` / `4096` |
| `ttlMillis` | 正常 TTL | `300000` |
| `negativeTtlMillis` | 负缓存 TTL | `30000` |
| `ttlJitterMillis` | TTL jitter 上限 | `0` / `5000` |
| `warmupRounds` | 预热轮数 | `5` |
| `measureRounds` | 测量轮数 | `10` |
| `thresholdMode` | 阈值模式 | `none` |

真实 Redis 结果还必须记录 Jedis/Redis 版本、Docker/远程地址来源、连接模式、连接池、Redis persistence、CPU/内存限制、网络位置、测试 DB、数据前缀与清理方式；密码和连接串不得进入报告。

## 5. 测量指标

| 指标 | 说明 |
| --- | --- |
| `operationThroughputPerSecond` | 完成 getOrLoad 调用数/秒 |
| `operationAvgNs` | 平均完成延迟 |
| `operationP50Ns` | p50 完成延迟 |
| `operationP95Ns` | p95 完成延迟 |
| `operationP99Ns` | p99 完成延迟 |
| `l1HitCount` | harness 确认的 L1 命中数 |
| `l2HitCount` | harness/fake Store 确认的 L2 命中数 |
| `loaderInvocationCount` | 实际 loader 调用次数 |
| `coalescedWaiterCount` | 复用同一 in-flight loader 的 waiter 数 |
| `singleflightCollapseRatio` | 被合并请求数 / 同一热点 key miss 请求数 |
| `negativeHitCount` | 由已有 negative entry 阻断 loader 的调用数 |
| `l2ReadCount` | L2 get 调用数 |
| `l2WriteCount` | L2 put/putIfVersion 调用数 |
| `loaderFailureCount` | loader 异常完成数 |
| `backendFailureCount` | L2 后端异常计数 |
| `writeBackFailureCount` | best-effort L2 写回失败数 |
| `rejectedLoadCount` | 不同 key 超出并发上限后的拒绝数 |
| `pendingLoadPeak` | 测量期间 in-flight 不同 key 峰值 |
| `allocationBytesPerOp` | 每次路径的对象分配量 |
| `resultMismatchCount` | value/empty/异常与 workload 预期不一致数，必须为 0 |

吞吐必须与延迟、value 大小、并发、key cardinality 和实际命中率同时报告。singleflight workload 必须同时报告 loaderInvocationCount 与 coalescedWaiterCount；只报告总吞吐无法证明击穿保护有效。

## 6. 结果格式

每条结果至少包含以下字段：

```text
evidenceId | workloadId | cacheMode | keyCardinality | operationCount
concurrency | hotKeyRatio | valueBytes | targetHitRatio | actualHitRatio
negativeRatio | loaderLatencyMillis | l2LatencyMillis | l2FailureRatio
maxConcurrentLoads | ttlMillis | negativeTtlMillis | ttlJitterMillis
sampleCount | operationThroughputPerSecond | operationAvgNs
operationP50Ns | operationP95Ns | operationP99Ns
l1HitCount | l2HitCount | loaderInvocationCount | coalescedWaiterCount
singleflightCollapseRatio | negativeHitCount | l2ReadCount | l2WriteCount
loaderFailureCount | backendFailureCount | writeBackFailureCount
rejectedLoadCount | pendingLoadPeak | allocationBytesPerOp
resultMismatchCount | thresholdMode | notes
```

首轮报告必须保留原始命令、Maven 解析版本、JVM 参数、机器信息、warmup 和每轮样本。L1、fake L2、coordinator、无 Driver 本地模式和真实 Redis Driver 必须分表，不能混合计算一个“缓存平均延迟”。

## 7. 风险边界

以下动作必须单独建档并等待用户确认：

- 新增 JMH、benchmark Maven profile、真实 Redis 压测、Docker external-tests、故障注入、长稳或 CI 门禁。
- 修改 `CacheService`、`CacheStore`、`CacheLoader`、`CachePolicy`、`CacheStatistics`、`CacheErrorCode` 或公共契约。
- 修改 key、TTL/jitter、负缓存、失效、版本、回填、淘汰、singleflight、并发背压、future 完成或降级语义。
- 修改 Redis cache envelope、codec、namespace、Lua、Driver、连接池或持久化策略。
- 定义命中率、延迟、吞吐、并发、TTL、loader、Redis RTT 或生产容量阈值。
- 根据结果修改缓存或 Redis Adapter 热路径。

本 readiness 不新增 JMH，不定义性能阈值，不修改 CacheService 或其他公共 API，不修改 singleflight、背压和降级语义，不修改 Redis cache envelope、codec、key 或 Lua，不连接 Redis，不把 allowStaleOnBackendFailure 描述为已实现行为，也不作为生产容量承诺。

## 8. 后续推进

```text
检查本页负载矩阵与采集口径
  -> 用户确认 benchmark/profile/采集边界
  -> 独立 benchmark 任务建档
  -> L1 + fake L2 + controllable loader 基线
  -> isolated singleflight/backpressure 基线
  -> 用户确认 Redis、隔离 DB、数据前缀和清理方式
  -> Redis Driver 分层证据
  -> 故障注入和长稳证据
  -> 结果审阅
  -> 如需 API、TTL、singleflight、观测或热路径调整，再次高风险确认
```

当前只完成 readiness。正式 benchmark、真实 Redis 证据、阈值、CI 门禁、容量结论和实现优化均未完成。
