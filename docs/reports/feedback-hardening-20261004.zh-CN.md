# 反馈专项复核报告（2026-10-04）

## 范围与结论

本次复核针对用户确认的 S1-S3、H1-H10。结论是：报告中的线程阻塞、异步乱序、锁内外部调用、幂等锁死、L2 故障穿透和并发容器访问均在当前实现中有对应证据，已完成局部修复；协议格式保持不变。所有并发、顺序和冷却行为均保留显式配置入口，不把整个系统默认改成全局串行。

## 确认项与修复证据

| 项目 | 当前结论 | 主要实现/回归 |
| --- | --- | --- |
| S1 Kafka poll 阻塞、批次无限等待 | 已修复 | `ApacheKafkaRpcMessageGateway` 使用应用 executor 和 partition tail；`KafkaRpcConsumerBatch` 使用单调处理预算，超限终结 worker；`KafkaRpcConsumerExecution` 提供并发/分区保序选择。 |
| S2 TCP replay 异步乱序、全局 Delayer、阶段类型假设 | 已修复 | `NettyProductionLifecycleSession` 连接内队列、连接 executor 超时和 `whenComplete` 桥接；`NettyFrameChannelHandler` TCP 顺序保护为 opt-in。 |
| S3 同步数据 IO 直跑调用线程 | 已修复并保留兼容边界 | Redis store、Envelope repository 支持显式 executor；旧兼容入口仍需生产组合根替换为受管 IO executor。 |
| H1 内容摘要误杀/改字节绕过 | 局部修复，协议限制保留 | `SecurityNetworkPolicy` 使用连接标识+接收序列合成 replay nonce，不再使用帧内容摘要；没有发送方 nonce/序列号，跨连接安全属性未完成。 |
| H2 TCP 同连接帧乱序 | 已修复为可选策略 | `NetworkTuning.preserveTcpFrameOrder` 默认为 false；开启时等待 handler 和响应写入完成后推进。 |
| H3 reply poll 线程执行用户回调 | 已修复 | `KafkaRpcAdapter` 将 completion 回调移出 poll 线程并通过阶段桥接终结结果。 |
| H4 Nacos 锁内 SDK 调用、lookup 穿透 | 已修复 | SDK 网络操作在锁外；lookup 有默认 1 秒缓存，TTL=0 可关闭；停止等待在途操作。 |
| H5 runtime 锁内 lifecycle 调用 | 已修复 | `DefaultGameRuntime` 锁内只迁移状态，组件 start/stop/close 和健康探针在锁外。 |
| H6 EventBus/Actor completion 劫持受管线程 | 已修复 | EventBus 支持 executor 且 continuation 回执行域；Actor scheduler 继续按 lane 推进。 |
| H7 GameActorGateway 常量 messageId | 已修复 | message/trace context 使用唯一标识，不再按命令类名复用。 |
| H8 GM 授权前 claim、token 未入指纹、异常滞留 | 已修复入口语义 | 授权先于 claim；指纹包含 approvalToken；`ZeroException`、运行时异常和 `Error` 均结束或清理在途 claim。token 核销仍由外部 provider 负责。 |
| H9 L2 故障导致 DB 穿透 | 已修复为可选冷却 | 正数 cooldown 开启快速失败和拒绝计数；旧构造器保持历史回源行为以避免无提示兼容破坏。 |
| H10 NPC map 并发修改、SyncEnvelope 热路径 | 部分修复 | `LocalNpcZone` map 操作受短 monitor 保护；尚未把 API 改成异步 lane facade。SyncEnvelope 的全量规范化/hash 成本仍需单独基准后决策。 |

## 已执行验证

环境为 Java 21、Maven，工作区未创建提交。定向测试结果：

- `zero-rpc-kafka`：51 tests，0 failures。
- `zero-gm,zero-game,zero-room`：57 tests，0 failures。
- `zero-cache,zero-data-redis -am`：Cache 55、Redis adapter 31，均通过。
- `zero-discovery-nacos`：28 tests，通过。
- `zero-net`：60 tests，通过。
- `zero-data`：53 tests，通过。
- `zero-npc`：8 tests，通过。

GM 旧测试已改为确认两次未授权请求都返回 `OPERATION_AUTHORIZATION_DENIED` 且审计执行两次；Kafka 旧测试已改为确认 poll-thread API 直接报告 `KafkaRpcResourceException`。这两个断言变化反映新语义，不是放宽测试。

## 未纳入本轮的风险

以下项目在反馈中有合理风险，但没有足够小且不改变现有契约的修复，因此只记录边界：

- UDP 出站可写性/预算、KCP 每报文分配与清零、KCP store 异步化，以及网络 backpressure 的容量长稳。
- ownership 租约的单调时钟、Mongo/PG 无超时构造、DirectPersistenceBindingExecutor 对 live mutable snapshot 的调用约束。
- LocalDurableJournal 的 group commit 和 compact 关键路径、Kafka/Nacos/Redis/数据库多节点故障注入。
- `ZeroRuntimeExecutors` role 无限增长、PeriodicAsyncTask 日志节流和异常引用、LocalActorScheduler/starter 默认 inline 装配。
- Room CREATED 事件 lane 顺序、LocalScene 不存在场景回收、Ranking 全局锁、FrameInput clone 热路径。
- 日志级别快速路径、Metric registry 全局锁、AlertEvaluator 锁内 sink、watcher 失败自愈。
- GM approval token 一次性核销，以及加载超时取消传播、findAll 批量 RTT、NPC 每 tick 快照分配和 InMemoryLogSink 无界增长。

这些项目应单独建立基准、容量阈值和外部依赖故障矩阵后再改默认行为。当前报告不把它们标记为已修复。
