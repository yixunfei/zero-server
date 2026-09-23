# 性能方案 S0-S3 迁移说明

适用版本：`0.1.0-SNAPSHOT`。本次以 2026-09-22 已有未提交优化为基线继续实施，不包含 S4 直连 RPC、MPSC 或跨异步缓冲借用。线格式不变。

## Actor

- `ExecutorActorScheduler` / `LocalActorScheduler` 默认每 Lane 最多 4096 条、每调度器最多 65536 条未完成消息。排队、执行及异步挂起均占许可；取消返回 Future 不取消已接纳消息，也不提前释放许可。
- 使用 `ActorSchedulerConfig` 构造器或 `ActorRuntime.module(config)` 配置预算；默认 runtime 会登记调度器关闭责任。每次 drain 默认最多 64 条，然后通过原受管执行器续调。同 Lane 顺序及异步完成语义不变。Local 仍在调用线程执行同步 handler、在完成回调线程恢复异步 handler，共享同一算法。
- 超限返回失败 Stage，错误码 `ZERO-ACTOR-CAPACITY-EXCEEDED`；执行器拒绝改为 `ZERO-ACTOR-EXECUTOR-REJECTED`，原先是通用 SYSTEM_ERROR。关闭拒绝新消息并失败通知排队消息，运行中的 handler 自然结束；不关闭调用方执行器。
- `statistics()` 提供低基数的 pending、active、completed、rejected、累计及最大排队等待。它是观察快照，不能作为精确准入依据，也不能直接称为延迟百分位。
- 默认消息 ID 改为随机进程/类加载器前缀加精确计数，新根消息的 traceId 使用该消息 ID。不要解析其 UUID 格式，也不要将其作为安全凭证。新增 `(laneKey, traceId, payload)` 构造器用于继承显式上游 trace；原显式四参数构造器保持原值。

## 协议与网络

- `ProtocolFrame.payloadLength/extensionLength` 和 `FrameInput.payloadLength` 无复制读取长度；原数组 getter 仍返回副本。帧的只读 ByteBuffer 视图自持有数据，不需要 retain/release，内容安全共享，但每个视图的游标须独占。
- `ProtocolFrameCodec.encodeTo/decodeFrom/encodedLength` 提供缓冲入口。仅实现数组 API 的自定义 codec 可以继续使用默认桥接；优化实现必须返回自持有帧。`encodedLength` 返回编码上界或 -1，错误上界会导致写入拒绝；默认自定义 codec 的临时数组分配仍由实现负责。
- Netty 默认 codec 直接从 ByteBuf 解码、向 allocator 的 ByteBuf 编码；入站帧仍保留防御复制。Protocol/Core 未增加 Netty 依赖。
- `ServerOptions.tuning()` 集中设置 `NetworkTuning`。默认 backlog 128、水位 32/64KiB、TCP 单连接待写预算 64MiB、单 TCP 服务器共享预算 256MiB。预算包含待提交输入、编码上界、长度字段及固定消息开销，是框架准入预算，不是 JVM/OS 总内存上限；另需为 allocator、TLS、线程和 socket 保留资源。
- `NettyConnection.sendFrames` 整批准入，同连接按列表顺序 write 后 flush 一次。发送 Stage 仍代表实际写完成，不代表远端已处理。预算耗尽或 Channel 不可写时以 `ZERO-NET-OUTBOUND-OVERFLOW` 失败，可靠消息不静默丢弃；取消返回 Future 不取消已准入的发送。
- 默认逐批立即 flush；正的 `flushConsolidationLimit` 显式启用 Netty EventLoop 合并。跨连接不合并系统调用。production 入站预算继续由既有 lifecycle 管理。
- TCP/HTTP 支持 NIO/AUTO/EPOLL，AUTO 暂保持 NIO。显式 EPOLL 不可用时启动失败；Linux 使用 `-Plinux-native` 加入原生运行库后另测，Windows 结果不代表 Linux。既有七参数 `ServerOptions` 构造是默认配置便利入口；record 新增组件，使用反射/record 模式的代码需调整。

## 排名、场景、AOI、帧同步和缓存

- Ranking 保留服务级同步和回调语义；UID 表和包内跨度跳表一并更新。Top K 按序遍历、缺失 UID 直接返回，命中排名期望 O(log N)。写入从哈希写变为索引维护，写密集场景需使用报告中公开的成本数据。冻结、幂等、版本及 SET/MAX/ADD 语义不变。
- Scene 外层目录仍并发，私有 `SceneState` 内层 HashMap 只在对应 Scene Lane 访问，查询继续导出不可变快照。
- AOI 默认仍按 ENTER/UPDATE/LEAVE 分组且组内 ID 排序。新增 `AoiIndex.forgetObserver`；自定义实现需实现释放方法，观察者退出时应调用它，再次 observe 从新观察生命周期开始。
- FrameSync 内部按 UID 排序维护，去除每 tick 排序并复用 tick 命令；历史 batch 保持不可变。默认继续接受稀疏远期输入，没有引入取模环形槽。`maxBufferedInputs` 同时限制保留的参与者数，避免 REPEAT_LAST 历史随新 UID 无限增长；达到上限的新参与者明确失败。
- Cache 仅六个统计计数采用 LongAdder；并发读取不是线性一致的统计元组，写入静止后精确。容量、版本及 TTL 语义不变。

## 验证与回退

命令、原始样本和适用边界见[实施报告](../reports/performance-plan-20260923.zh-CN.md)。两遍扫描的直接 UTF-8 实验虽然减少分配，但混合字符吞吐退化，因此默认保留 JDK 编码。没有启用无证据的 primitive 容器、广播编码池、MPSC、无 Future 单向路径或直连 RPC。

需要回退时应回退本次对应源码与配置；没有数据格式转换或持久化迁移。不要通过无限提高预算掩盖持续过载，先排查实际完成速率、队列等待及慢消费者。
