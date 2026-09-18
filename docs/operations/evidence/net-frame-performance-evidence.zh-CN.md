# net-frame 性能证据口径草案

```text
zero-performance-evidence=track|id=net-frame|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `net-frame` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”目标，但当前只作为后续 benchmark、loopback 负载与长稳任务的输入，不是性能结果报告，也不是生产容量承诺。

## 1. 定位

`net-frame` 覆盖 `ProtocolFrame` 进入 Netty TCP pipeline 后的 framing、encode/decode、粘包/拆包重组、loopback 往返、多连接推进、handler executor 投递、短时积压和 IO EventLoop 非阻塞边界。

该 track 必须与 `protocol-codec` 分开：前者测 Netty pipeline 和连接推进的增量成本，后者测 `ProtocolFrameCodec` 本身。结果报告必须同时提供 EmbeddedChannel 与本机 loopback 口径，不能用单个 loopback 数字推导公网容量。

当前任务只固定“测什么、怎么记录、哪些边界不能误碰”。它不修改 frame、Netty pipeline、线程模型或背压实现，不新增 benchmark profile，不定义阈值。

## 2. 源码证据

| 证据 | 当前状态 | 用途 |
| --- | --- | --- |
| [zero-net/pom.xml](../../../zero-net/pom.xml) | 已有 | Netty transport/codec 依赖和网络模块入口 |
| [zero-protocol/pom.xml](../../../zero-protocol/pom.xml) | 已有 | `ProtocolFrame` 与 frame codec 入口 |
| [zero-net/src/main/java/group/zn/zero/net/netty/NettyTcpServer.java](../../../zero-net/src/main/java/group/zn/zero/net/netty/NettyTcpServer.java) | 已有 | TCP pipeline、length-field framing 和 EventLoop 配置观察点 |
| [zero-net/src/main/java/group/zn/zero/net/netty/NettyProtocolFrameEncoder.java](../../../zero-net/src/main/java/group/zn/zero/net/netty/NettyProtocolFrameEncoder.java) | 已有 | `ProtocolFrame -> ByteBuf` 桥接入口 |
| [zero-net/src/main/java/group/zn/zero/net/netty/NettyProtocolFrameDecoder.java](../../../zero-net/src/main/java/group/zn/zero/net/netty/NettyProtocolFrameDecoder.java) | 已有 | `ByteBuf -> ProtocolFrame` 桥接入口 |
| [zero-net/src/main/java/group/zn/zero/net/netty/NettyFrameChannelHandler.java](../../../zero-net/src/main/java/group/zn/zero/net/netty/NettyFrameChannelHandler.java) | 已有 | IO 线程到业务执行器的投递边界 |
| [zero-protocol/src/main/java/group/zn/zero/protocol/codec/ProtocolFrameCodec.java](../../../zero-protocol/src/main/java/group/zn/zero/protocol/codec/ProtocolFrameCodec.java) | 已有 | frame codec 抽象 |
| [zero-protocol/src/main/java/group/zn/zero/protocol/codec/ZeroBinaryFrameCodec.java](../../../zero-protocol/src/main/java/group/zn/zero/protocol/codec/ZeroBinaryFrameCodec.java) | 已有 | 默认二进制 frame codec |
| [zero-net/src/test/java/group/zn/zero/net/netty/NettyServerImplementationsTest.java](../../../zero-net/src/test/java/group/zn/zero/net/netty/NettyServerImplementationsTest.java) | 已有 | TCP/UDP/HTTP 本地功能测试，不是 benchmark |
| [docs/threading-model.zh-CN.md](../../../docs/threading-model.zh-CN.md) | 已有 | IO 线程和业务执行域边界 |
| [docs/reference/production-network-lifecycle-contract.zh-CN.md](../../../docs/reference/production-network-lifecycle-contract.zh-CN.md) | 已有 | 握手、鉴权、心跳、限流和背压的高风险确认边界 |
| [docs/operations/performance.zh-CN.md](../../../docs/operations/performance.zh-CN.md) | 已有 | 性能 track 聚合入口 |

这些证据只能说明当前具备源码观察点和功能测试，不说明网络吞吐、连接容量或背压能力达标。

## 3. workload 矩阵

| workloadId | 场景 | 隔离目标 | 当前阶段 |
| --- | --- | --- | --- |
| `embedded-channel-frame-encode` | 用 Netty `EmbeddedChannel` 编码固定 payload 的 `ProtocolFrame` | 观察 encoder、ByteBuf 写入和 pipeline 增量，不包含 socket | 计划 |
| `embedded-channel-frame-decode` | 解码完整 length-delimited frame | 观察 length decoder 与 frame decoder 增量 | 计划 |
| `fragmented-frame-reassembly` | 将一个 frame 按 header、payload 边界和随机片段多次写入 | 验证拆包重组成本和正确性，不改变 wire format | 计划 |
| `coalesced-multi-frame-decode` | 一次写入多个连续 frame | 验证粘包场景下多 frame 解析吞吐与顺序 | 计划 |
| `tcp-loopback-roundtrip` | 单连接本机 TCP 请求/响应 | 观察真实 socket、EventLoop、codec 和 handler 投递的组合成本 | 计划 |
| `multi-connection-throughput` | 多个本机连接按固定并发发送 frame | 观察连接数增长时的吞吐、延迟和 EventLoop 负载 | 计划 |
| `slow-handler-backlog` | handler executor 可控延迟，形成短时积压 | 记录业务执行器 backlog 和 frame 完成延迟；不修改背压 | 计划 |
| `io-thread-nonblocking` | handler 阻塞 fake 与线程名/任务延迟探针 | 证明业务处理未在 IO EventLoop 线程执行 | 计划 |

每个 workload 必须至少覆盖 32 B、256 B、1 KiB、16 KiB payload；大状态快照可另加 64 KiB 样本，但不得超过 `ServerOptions.maxFrameLength`。

## 4. 环境元数据

后续正式结果至少记录以下字段：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `net-frame-20260710-loopback-win` |
| `track` | 性能 track | `net-frame` |
| `javaVersion` | Java 版本 | `21.0.x` |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g` |
| `nettyVersion` | 实际解析的 Netty 版本 | 由 Maven dependency tree 记录 |
| `transport` | transport 类型 | `nio-embedded` / `nio-loopback` |
| `eventLoopThreads` | worker EventLoop 数量 | `1` / `4` / `8` |
| `handlerExecutorModel` | 业务处理执行模型 | `direct-test` / `single` / `fixed` |
| `connectionCount` | 同时活跃连接数 | `1` / `100` / `1000` |
| `payloadBytes` | payload 字节数 | `32` / `256` / `1024` / `16384` |
| `framesPerConnection` | 每连接 frame 数 | `10000` |
| `fragmentationPattern` | 拆包/粘包模式 | `complete` / `header-split` / `random` / `coalesced-8` |
| `warmupRounds` | 预热轮数 | `5` |
| `measureRounds` | 测量轮数 | `10` |
| `thresholdMode` | 阈值模式 | `none` |

`thresholdMode=none` 表示第一阶段只记录可复现结果，不设置通过/失败阈值。

## 5. 测量指标

| 指标 | 说明 |
| --- | --- |
| `encodeThroughputOpsPerSecond` | EmbeddedChannel frame encode 每秒操作数 |
| `decodeThroughputOpsPerSecond` | 完整、拆包或粘包 frame decode 每秒操作数 |
| `roundTripLatencyNs` | loopback 请求到响应完成延迟，至少记录 avg/p50/p95/p99/max |
| `frameThroughputPerSecond` | loopback 每秒完成 frame 数 |
| `networkThroughputBytesPerSecond` | 依据实际 frame 字节数计算的双向吞吐 |
| `allocationBytesPerOp` | 每 frame 分配量；不能只用 GC 次数替代 |
| `eventLoopPendingTasks` | EventLoop pending task 观察值；注明采集方式和开销 |
| `handlerBacklogDepth` | 业务执行器待处理任务深度或可替代积压量 |
| `rejectedFrameCount` | 超长、非法或测试策略拒绝的 frame 数 |
| `ioEventLoopBlockedMillis` | IO EventLoop 探针检测到的调度延迟，不等同于业务延迟 |

吞吐必须同时给出 frame/s 与 bytes/s。延迟分位数必须说明样本量；不足以稳定计算 p99 时不得填造结果。无法可靠采集某个指标时写 `not-collected` 并说明原因。

## 6. 结果格式

每条结果至少包含以下字段：

```text
evidenceId | workloadId | transport | eventLoopThreads | handlerExecutorModel
connectionCount | payloadBytes | fragmentationPattern | sampleCount
throughputFramesPerSecond | throughputBytesPerSecond
latencyAvgNs | latencyP50Ns | latencyP95Ns | latencyP99Ns | latencyMaxNs
allocationBytesPerOp | eventLoopPendingTasks | handlerBacklogDepth
rejectedFrameCount | ioEventLoopBlockedMillis | thresholdMode | notes
```

首轮报告必须保留原始命令、Maven 解析版本、JVM 参数、机器信息、warmup 和每轮样本，不得只提交一张汇总表。EmbeddedChannel 与 loopback 结果必须分表，不能直接横向比较为“网络开销百分比”，除非采样与 payload 完全一致。

## 7. 风险边界

以下动作必须单独建档并等待用户确认：

- 新增 JMH、benchmark Maven profile、网络压测、长稳或 CI 性能门禁。
- 修改 frame 格式、length field、协议 ID 或 codec。
- 修改 EventLoop、业务执行器、队列容量或背压策略。
- 修改自动读、写缓冲水位、channel option 或连接关闭语义。
- 实现握手、鉴权、心跳、重连或限流。
- 定义吞吐、延迟、连接数、队列或生产容量阈值。
- 根据结果修改 Netty 或协议热路径。

本 readiness 不新增 JMH，不定义性能阈值，不修改 frame 格式、length field、协议 ID 或 codec，不修改 EventLoop、业务执行器、队列容量或背压策略，不实现握手、鉴权、心跳、重连或限流，也不作为生产容量承诺。

## 8. 后续推进

```text
检查本页负载矩阵与采集口径
  -> 用户确认 benchmark/profile/采集边界
  -> 独立 benchmark 任务建档
  -> EmbeddedChannel encode/decode 与拆包/粘包基线
  -> 本机 loopback 单连接基线
  -> 用户确认多连接压测环境和上限
  -> 多连接/积压证据
  -> 结果审阅
  -> 如需热路径调整，再次高风险确认
```

当前只完成 net-frame 性能 readiness。显式 opt-in 的 TCP lifecycle minimum slice 已实现，但正式 benchmark 结果、阈值、CI 门禁、完整生产连接治理与容量结论均未完成；不得用 minimum slice 推导完整网关、吞吐、长稳或 production ready。
