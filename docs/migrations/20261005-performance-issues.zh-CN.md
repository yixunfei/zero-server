# 数据、同步与 Kafka 性能修复迁移（2026-10-05）

适用范围：`0.x` 开发预览，当前 reactor 为 `0.1.2-SNAPSHOT`，受保护 API 基线为 `0.1.0-SNAPSHOT`。本次范围已经用户确认；公共入口和默认执行行为直接调整。数据 envelope、SyncEnvelope 摘要 canonical 格式、KCP ZKCP v1 和 RPC 线格式不变。评估与验证见[专项报告](../reports/performance-issues-20261005.zh-CN.md)。

## 1. Repository 必须注入 IO 执行器

`ZeroDataEnvelopeCrudRepository(codec, store, ioExecutor)`、`EnvelopeRepositoryFactory(stores, ioExecutor)`、`EnvelopeRepositoryFactory.inMemory(ioExecutor)` 以及 Mongo/Redis/PostgreSQL Adapter 的注册和工厂入口增加必填 `Executor`。删除不带执行器的入口，不使用 commonPool 兼容桥接。纯内存契约测试可注入 `Runnable::run`；同步远程驱动必须使用框架管理的非内联执行器。

生产数据 provider 现在要求 `RuntimeBasics.EXECUTORS`，并拒绝 `remoteIoMayInline=true`。`ZeroProductionRuntimeFactory` 的默认 production/external-test 装配采用 `ZeroRuntimeExecutors.remoteIo(prefix)`：remote IO 每任务使用 Java 21 虚拟线程，首次提交才创建线程，runtime 统一关闭。logic、actor、background 仍在调用线程执行；需要独立执行域时显式选择 `localPrototype` 或应用装配。底层 `ProductionAssembly.builder` 仍需调用方显式传入执行器：

```java
var executors = ZeroRuntimeExecutors.remoteIo("game");
GameRuntime runtime = ProductionAssembly.builder("production", config, executors)
        .install(MongoRuntime.module())
        .install(DataRuntime.repositories(Map.of("game", "mongo")))
        .build();
```

执行器创建本身不等于背压：按驱动连接池、超时和业务预算限制在途请求。Repository 不再全方法 `synchronized`，自定义 Store 和 codec 必须支持并发；版本顺序由后端原子 CAS 保证。保存前在实体所属调用域编码不可变 envelope，避免 IO 线程读取 Actor 可变实体。查询解码和完成回调可能运行在 IO 域，修改状态前应回投 Actor。

仓库首次创建仍可能初始化 schema，应在启动管理域预热。工厂关闭等待已进入 Store 的操作；排队但未进入 Store 的请求以 `BACKEND_UNAVAILABLE` 失败。先停止持久化、关闭工厂，再关闭它依赖的外部客户端与执行器；注入资源不由工厂关闭。

## 2. PostgreSQL 直接入口使用连接池

生产 runtime 原本已使用 HikariCP，本次统一直接 settings 工厂与生产装配。HikariCP 依赖下移到 `zero-data-postgresql`，公共工厂 `PostgresqlPool.create(settings, maximumConnections, acquisitionTimeout)` 供组合根复用。

`PostgresqlDataAdapter.repositoryFactory(settings, ioExecutor)` 默认最大 8 个物理连接、借用等待 3 秒；返回工厂拥有并关闭该池。`repositoryFactory(dataSource, tableName, ioExecutor)` 使用外部池，调用方负责关闭；驱动 Store 构造改为注入 DataSource。连接必须开启 auto-commit，仍拒绝借出的手动事务连接。JDBC `connectTimeout/socketTimeout` 按部署配置，连接池等待上限不替代 socket 超时。

## 3. 批量与分页语义

`ZeroDataEnvelopeStore` 增加 `findByIds`、`findPage`、`saveIfVersionBatch` 和 `deleteAll`。默认方法保留小型自定义 Store 的逐项/全量回退；生产三种驱动覆盖为 256 项分块操作。

- PostgreSQL 使用批量 ID 查询/删除、JDBC CAS batch 和 `LIMIT/OFFSET`，批量写必须逐项得到精确 CAS 结果；启用 `reWriteBatchedInserts` 也不得把冲突误判为成功。
- Mongo 使用 `$in`、`skip/limit` 和 ordered bulk；聚合结果无法定位单项 CAS 冲突时整个调用报告 `VERSION_CONFLICT`。
- Redis 按 bucket/slot MGET、pipeline CAS/删除；所有成功项写入本地 journal 后再报告中途失败。分页仅拉取页内 payload，但索引仍需 SSCAN 与排序，沿用 100000 扫描预算。

`findByIds` 按输入顺序返回，缺失项省略、重复项保留。分页按编码 ID 排序，total 与 items 在并发修改下弱一致。`saveAll/deleteAll` 不承诺整个批次事务原子性；异常时可能部分提交，重读确认后再决定重试，不能直接重复非幂等操作。Redis journal 的 `appendAll` 每个文件批量 force 一次；单项持久化仍可能逐项 force。

## 4. 持久化并发预算

`DefaultPersistenceManager` 默认跨独立脏键最多 16 个在途保存；七参数构造器最后一个参数 `maxConcurrentWrites` 可调整，必须为正。设为 1 可选择串行。整个 flush 队列仍按请求顺序推进，避免同键跨 flush 重叠；失败、新标脏代际、重试/隔离与停机预算继续生效。

每项成功才清除对应 dirty entry。未直接使用聚合 `saveAll` 清理脏项，因为部分提交无法在所有后端准确定位。对象级脏追踪仍是默认设计。并发预算应匹配驱动容量；本次不承诺一万脏实体一定在 30 秒内完成。

## 5. SyncEnvelope 共享准备与 KCP Cipher

发送方可调用 `SyncEnvelope.preparePayload(payload)`，得到深不可变 Map，供多个观察者构造 envelope 复用同一快照和 SHA-256。接受 Map、Collection 以及 String、包装类型、BigInteger、BigDecimal、UUID 等不可变标量，拒绝数组和自定义可变叶子；顶层 null 仍拒绝，嵌套 null 可用。Collection 按迭代顺序冻结为 List。

普通 Map 和外部显式摘要仍立即计算、校验；不提供信任任意外部摘要的捷径。canonical 保持原格式，使用单 StringBuilder 和线程独占 digest。此入口不等于已经实现生产多观察者广播或共享编码字节。

KCP protection Context 持有各方向 Cipher 和 SecretKeySpec，每包仍 init/AAD/doFinal。Context 必须属于单 EventLoop 或单线程，不能跨线程共享；close 清理密钥和引用。认证、nonce、方向隔离、重放策略和线格式保持原契约。

## 6. Kafka reply 配置现在是前缀

production `zero.rpc.kafka.reply-topic` 配置值现在是前缀，每次 runtime 创建后追加 `.` 和 32 字符随机 UUID。前缀只允许 `[a-zA-Z0-9._-]+` 且最长 216 字符，最终 topic 最长 249。包括显式配置在内，production lifecycle 对 request/response 实际重写 `RpcRequest.replyTopic` 为实例所属 topic，其余请求字段和签名 metadata 原样保留；oneway 不重写。

部署应给前缀下的独占 topic 配置 ACL、创建权限或外部 provisioning，禁用自动建 topic 时必须在订阅前准备实际 topic。设置合理 retention 和过期 topic 清理；每次 runtime 重建产生新 topic，不自动删除历史 topic。不要继续只授权旧的单个共享 reply topic。

直接使用 `KafkaRpcAdapter` 时仍由调用方显式传入唯一 reply topic，并负责其生命周期。correlationId 是关联键，单独设置分区键不能解决不同 consumer group 对同 topic 的广播。

## 7. 成功日志改为计数观测

production `KafkaProductionTelemetryObserver` 在创建 `ZeroLogRecord` 前省略 STARTED/SUCCESS 事件；使用既有 `RpcTransportSnapshot` 计数观测成功与 pending 状态。依赖逐 RPC 成功传输日志的应用应改用快照指标或自行装配受控 observer。

失败、超时、拒绝和降级仍同步进入 LogPipeline，保留真实 ErrorCode 与处理器前后的不可关闭安全门。本次未增加异步 appender；异常风暴期间同步日志仍需容量治理，不改变审计耐久或安全保证。

## 8. 本次并发与边界修复

Kafka consumer 批次现在按分区保存处理状态，成功终态才提交实际读取到的 offset；失败、超时预算或重平衡不会确认在途消息。旧的 `track(records, completion)` 入口保留，生产路径可注入按分区完成阶段。

`DefaultPersistenceManager` 仍保留可注入 `PersistenceBindingExecutor`、重试策略和并发上限。新 `markDirty` 代际不会继承旧代际的失败次数；迟到旧失败不会覆盖替换后的脏入口，显式 `retryFailed` 仍用于人工恢复隔离状态。

`FrameMatchConfig` 新增可配置的 `maxFutureFrames`，默认值为 120。原四参数构造器保留并使用该默认值，超过窗口的输入（包括 `Long.MAX_VALUE`）在占用缓冲和幂等记录前拒绝。

`LocalRoomService` 保留同步 `create` API。创建提交与首个房间命令通过内部监视器线性化，房间仍由注入的 `ActorScheduler` 执行；通知失败不会回滚已经发布的房间状态。

上述行为变化均为 0.x 运行时语义调整，不改变线协议或持久化格式。升级后应运行四模块定向测试；尚未覆盖真实 Kafka rebalance、长稳容量和跨进程房间部署。
