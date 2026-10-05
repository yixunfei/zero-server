# 2026-10-04 反馈专项迁移说明

本说明对应 S1-S3、H1-H10 的确认修复。项目仍处于 `0.x` 开发预览；本轮不修改协议线格式、持久化格式或既有公共调用语义，但改变了若干默认执行边界和错误返回时机。升级前应检查组合根是否显式提供执行器、容量和超时配置。

## 行为变化

| 范围 | 新行为 | 接入方需要确认 |
| --- | --- | --- |
| Kafka consumer | poll 线程只做 poll、解码、投递和提交；业务在 `KafkaRpcConsumerExecution` 的 executor 中执行。默认 `max.poll.records=100`，批次处理预算从 `max.poll.interval.ms` 预留。 | 为每个 consumer 注入有界应用 executor；需要同分区保序时使用 `partitionOrdered(executor)`，吞吐优先时使用 `concurrent(executor)`。两种策略都不把不同分区强制串行。 |
| Kafka batch | pending 失败、重平衡或处理预算超限直接以 `KafkaRpcResourceException` 终结 worker，不再在 poll 线程永久 `join`。 | 处理失败按至少一次语义重投；业务 handler 必须幂等。旧测试若期待 `CompletionException`，应改为检查资源异常。 |
| TCP replay | 同一连接的 replay 判定按帧到达顺序排队；每帧超时由连接 executor 调度，第三方 `CompletionStage` 使用 `whenComplete` 桥接。 | replay provider 不必返回 `CompletableFuture`；仍必须在预算内结束。协议没有真实发送方 nonce/序列号，本轮只消除内容摘要误杀和异步乱序，不能提供跨连接防重放。 |
| TCP 业务帧 | `NetworkTuning.withTcpFrameOrderPreserved(true)` 时，同一连接按 handler 和响应写入完成顺序处理；默认仍允许并发。 | 只有需要连接内严格响应顺序时开启；开启后该连接存在 head-of-line blocking。 |
| 网络心跳 | 合法业务帧会刷新连接活动计数。 | 业务流量不再因缺少显式 heartbeat 被误判超时；定时检查仍受原心跳配置控制。 |
| Redis/Envelope repository | 支持显式有界 IO executor。 | 生产装配必须把同步 Jedis/Mongo/PG 调用放在 remote-IO 执行域；兼容构造器的 deprecated common-pool 行为只用于过渡。 |
| L2 缓存 | 可用正数 `Duration l2FailureCooldown` 开启 Redis 读取故障冷却；冷却期间快速失败，防止 loader 全量穿透。旧构造器保持 `Duration.ZERO` 的历史回源行为。 | 生产按 DB 容量和可接受陈旧度选择窗口，并监控 backend failure、circuit rejected 和 loader 负载。 |
| Nacos | SDK 网络调用移出生命周期锁；lookup 默认 1 秒本地缓存。 | 需要实时 lookup 时设置 `zero.discovery.nacos.lookup-cache-ttl-millis=0`；停止时应允许在途 SDK 操作在关闭预算内结束。 |
| runtime/EventBus | 生命周期状态迁移与外部调用分离；EventBus 可注入 executor，异步续调回执行域。 | 不在状态锁或发布线程中执行阻塞外部代码；为生产组合根提供受管执行器。 |
| Game/GM/NPC | Game actor message 使用唯一 ID；GM 先授权后 claim，指纹包含 approval token，异常会清理 `IN_PROGRESS`；NPC map 更新受短 monitor 保护。 | 授权失败不会锁死幂等键；审批 token 仍需真实 provider 核销；NPC API 仍是同步 facade。 |

## H1 协议限制

当前帧没有发送方 authenticated nonce 或单调发送序列号。实现不再把帧内容 SHA-256 当作 nonce：相同内容的合法帧不会在窗口内互相误杀，修改一个字节也不会因此获得“新的发送序列”语义。现有 replay provider 收到的是连接标识加接收序列的合成 nonce，只能约束当前连接和当前进程的到达顺序。跨连接重放、跨进程共享窗口和主动攻击者伪造序列必须通过后续协议扩展（并同步客户端、密钥和兼容策略）解决，本轮不宣称已经解决。

## 建议迁移步骤

1. 在 starter/组合根创建有界业务 executor、remote-IO executor 和网络 handler executor，并明确拒绝策略；不要把 `Runnable::run` 用作生产 IO 执行器。
2. 为 Kafka consumer 选择 `concurrent` 或 `partitionOrdered`，根据 handler 的幂等性和分区顺序要求设置；把 `max.poll.records`、`max.poll.interval.ms` 与处理预算按真实 workload 校准。
3. 需要严格 TCP 响应顺序的连接启用 `NetworkTuning.withTcpFrameOrderPreserved(true)`，并测量该连接的队头阻塞和排队预算。
4. Redis L2 生产实例使用带 `l2FailureCooldown` 的构造器；先以较短窗口压测，再结合数据库保护阈值调整。窗口不是熔断替代品，仍需后端健康探测和告警。
5. 检查 GM 审批 provider 是否有一次性 token 消费；若没有，不能把本轮入口修复当作 token 防重放完成。
6. 运行下列定向测试，并在外部依赖可用时补充单实例 Docker 故障注入和长稳测试。

## 验证命令

```text
mvn -B -ntp -pl zero-rpc-kafka test
mvn -B -ntp -pl zero-gm,zero-game,zero-room test
mvn -B -ntp -pl zero-cache,zero-data-redis -am test
mvn -B -ntp -pl zero-discovery-nacos test
mvn -B -ntp -pl zero-net test
mvn -B -ntp -pl zero-data test
mvn -B -ntp -pl zero-npc test
```

本轮已在 Java 21 环境执行上述定向模块测试；外部 Kafka/Nacos/Redis/Mongo/PG 多节点故障注入、容量长稳和跨进程 replay 验证尚未执行。
