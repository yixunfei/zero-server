# 2026-09-22 安全、持久化与异步确认迁移

本次为 0.x 开发阶段行为修复，无版本号变更。所有依赖这些模块的应用应重新编译。

## 接入变更

- HTTP：三参数 `NettyHttpServer` 构造器对安全元数据默认拒绝；需要透传身份时使用四参数构造器注入 `SecurityMetadataVerifier`。无 metadata 的请求保持匿名。verifier 应校验签名、`assertionReference` 的信任/重放策略以及有效期；框架不再把 Base64 解码结果视为身份。身份只属于当前请求，不存在 keep-alive 连接继承。头名称大小写无关，重复安全头拒绝。
- Kafka：所有接收端默认校验身份；缺元数据或 verifier 返回空/失败均拒绝 handler。直接 Adapter 使用 `securityMetadataVerifier(...)`，runtime 使用 `KafkaRuntime.module(properties, verifier)`，在 handler 订阅前配置验证器。调用端通过带 assertion signer 的 `RpcClientFactory` 和 `SecurityContextBridge` 生成签名元数据。未配置的 runtime 安全地拒绝请求。
- Kafka 提交：强制 `enable.auto.commit=false`，扩展属性不能开启它。默认 `auto.offset.reset=earliest`。处理及响应的 producer ack 成功后提交明确 offset，等待期间 pause 分区但继续 poll；失败/停机/重平衡不确认在途批次。请求响应模式的业务错误在错误响应成功发送后确认；oneway 业务失败不确认，非法/过期请求可终止丢弃。语义为至少一次，批次可重投，业务必须幂等；没有 exactly-once 保证。
- Kafka 默认消费组包含实例随机后缀。同 topic 的独立实例各自接收请求；需要竞争消费时显式配置共享 group，需要跨重启保留消费位置时显式配置稳定 group。随机组重启从 earliest 读取，过期请求被拒绝。
- 缓存：`CachePolicy` 增加 `loadTimeout`（默认 3 秒），`CacheEntry` 增加实体版本。默认参数构造器仍提供方便的非版本化条目/默认超时配置。调整长耗时 loader 的超时预算；超时或取消后的迟到 loader 结果不得回填，已开始的远程回填不保证可撤销。失效不删除已更新 L1；Redis 正实体版本相等时条件写返回冲突，版本 0 保留未版本化写语义。本地按写入顺序淘汰最早条目，覆盖写更新顺序，读命中不修改顺序；写入和索引在短临界区内同步，读命中保持无锁。
- Redis：本地日志仅保存故障现场，不代表 Redis 已提交，不自动回放未确认写。Redis 超时可能具有不确定结果，调用方应读取版本核对。原子删除遇到并发更新时报告版本冲突，不静默删除更新数据。保存/删除脚本在修改前校验 key 类型，避免 WRONGTYPE 在中途发生；Lua 不提供运行错误后的事务回滚。日志追加使用 `force(true)`，恢复只容忍尾部不完整帧，完整帧损坏仍报错；追加前恢复完整边界，每个日志根目录由一个实例独占写入。文件系统/设备故障及真实断电仍需部署环境验证。
- Prometheus：构造器必须传入外部管理的异步 `Executor`，不允许 `Runnable::run`。第四参数可配置 Bearer token；非回环必须配置，回环可省略。`/metrics` 和 `/health` 均校验配置的 token。端点 stop 不关闭调用方 executor；应用须管理容量与生命周期，TLS 由部署入口提供。

## 逐项核验

| 编号 | 结论及处理 | 证据入口 |
| --- | --- | --- |
| 1 | 成立；条件写异常始终抛 WRITE_FAILED | RedisDriverEnvelopeStoreTest |
| 2 | 成立；异步验证、请求身份隔离、大小写/重复头处理 | HttpSecurityBoundaryTest |
| 3 | 成立；默认 fail-closed，runtime verifier 装配 | KafkaRpcAcknowledgementTest、KafkaRpcAdapterTest |
| 4 | 当前实现不成立；先完成已 poll 消息再调度，移除/入队共用锁 | ExecutorActorSchedulerTest.asynchronousRescheduleRejectionDoesNotStrandLane |
| 5 | 成立；批次异步确认，失败不提交，默认 earliest | KafkaRpcConsumerBatchTest、KafkaRpcAcknowledgementTest、KafkaRpcAcknowledgementExternalIT |
| 6 | 默认固定组确会竞争分区；默认改为实例隔离，显式共享组仍支持竞争消费 | KafkaRpcConsumerBatchTest.defaultGroupsAreInstanceSpecific |
| 7 | 当前实现不成立；每次 publish 的 thenCompose 串行链保证失败写入/汇总顺序 | EventReportAuditTest.asynchronousFailuresAreOrderedAndAllRetained |
| 8 | 成立；条件 L1 删除保留并发更新，正实体版本相等拒绝覆盖 | LayeredCacheConcurrencyTest、RedisCacheStoreExternalIT |
| 9 | 成立；Lua 原子校验并删除，预检类型并报告版本冲突 | RedisAtomicMutationExternalIT、RedisDataAdapterExternalIT |
| 10 | 成立；force、尾部恢复及续写 | LocalDiskDataJournalTest |
| 11 | 成立；有序容量淘汰、默认加载超时和取消释放 | InMemoryCacheServiceTest、CacheLoadCoordinatorTest |
| 12 | 所述 Netty 线程安全问题不成立；Channel.writeAndFlush 支持跨线程并调度 outbound 到 EventLoop，UDP 不保证端到端交付顺序 | NettyServerImplementationsTest.udpServerShouldExchangeDatagramFrame |
| 13 | 成立；外部异步执行器和 Bearer token | PrometheusHttpEndpointTest |

## 验证

使用 Java 21。验证命令（PowerShell）：

```powershell
mvn -Pquality verify '-DskipITs'
mvn -pl zero-data-redis,zero-rpc-kafka -am -Pexternal-tests verify `
  '-Dit.test=RedisDataAdapterExternalIT,RedisCacheStoreExternalIT,RedisAtomicMutationExternalIT,KafkaRpcAdapterExternalIT,KafkaRpcAcknowledgementExternalIT' `
  '-Dfailsafe.failIfNoSpecifiedTests=false' `
  '-Dzero.redis.uri=redis://127.0.0.1:26379/0' `
  '-Dzero.kafka.bootstrapServers=127.0.0.1:29092'
```

2026-09-22 的真实 Redis 7.4 / Kafka 3.9.1 外部测试共 7 项通过，无失败、错误或跳过。覆盖 Redis 类型错误无部分更新、版本冲突、CRUD/日志一致性及缓存相等版本冲突；Kafka 积压读取、未确认停止后重投、响应完成后确认。

全仓 `quality verify` 的 57 个 reactor 项全部通过，696 项单元测试无失败、错误或跳过；Checkstyle、PMD、SpotBugs、JaCoCo 检查通过。随后补充普通写与实体版本混用回归，执行 `mvn -pl zero-cache,zero-data-redis -am -Pquality verify '-DskipITs'`，135 项受影响测试及质量检查全部通过。两组单元测试存在重叠，不累加计数。

本地日志为 `target/hardening-quality-final.log`、`target/hardening-cache-quality-final.log` 和 `target/hardening-external-final.log`。确定性测试覆盖失效路径；真实断电、Redis Cluster、多 broker 故障转移和性能压测不在本次本地验证范围内。
