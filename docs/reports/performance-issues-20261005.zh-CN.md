# H1–H8 数据、同步与 Kafka 性能评估（2026-10-05）

本次对用户提供的八项问题逐项核验并修复，用户已确认“按此范围推进”。H1、H2、H5、H6 的串行 IO、H7 和 H8 有真实缺陷；H3 的构造成本真实，多观察者在生产路径重复 N 次未得到证实；H4 只在直接 settings 工厂成立，生产 runtime 原本已有 HikariCP。对象级脏追踪、摘要校验、日志安全门和 KCP Context 单线程归属属于保留的设计或安全约束。

验证基于分支 `zero/concurrency-async-performance-audit`、HEAD `a4d254ae23242acfa552fa48a4a04a3261198f87` 加当前未提交工作树，reactor 版本为 `0.1.2-SNAPSHOT`。工作树同时包含先前任务的修改，本报告只归属此次 H1–H8 的结果。环境为 Windows、JDK 21（`D:/env/jdk21`）、Maven 3.9.8（`D:/env/apache-maven-3.9.8`）。0.x 公共入口与配置变化见[迁移说明](../migrations/20261005-performance-issues.zh-CN.md)。

## 逐项结论

| 项目 | 真实性与边界 | 最终修复 |
| --- | --- | --- |
| H1：Repository 伪异步与全局串行 | 同步 Store 网络调用必须离开 Actor/逻辑调用域；Repository 级锁不是版本一致性所必需。 | 删除 Envelope CRUD 的全方法 synchronized；显式注入受管 Executor，所有 CRUD Store 调用异步提交。实体、ID 和集合在调用域编码快照，后端 CAS 负责版本安全。production 数据 provider 拒绝内联 remote IO，production 便利工厂默认提供虚拟线程 remote IO。 |
| H2：成功 RPC 的同步结构化日志 | 默认成功路径日志开销真实；“约六条”和“60k 条/s”是路径估算，未做该吞吐实测。System.Logger 调用也不能直接等同于每条 fsync。处理器前后安全门是两个信任边界。 | production observer 在创建记录前省略 STARTED/SUCCESS，成功通过既有 transport snapshot 计数观测。失败、超时、拒绝和降级继续写安全日志；没有加入异步 appender。 |
| H3：SyncEnvelope canonical/hash 成本 | 普通构造的复制、排序和哈希真实；未找到生产多观察者广播调用来证明 N 倍重复。外部摘要不能直接信任。 | canonical 改为单 StringBuilder，MessageDigest 按线程复用并 reset；新增 preparePayload 深不可变快照，多个 envelope 共享一次摘要。普通 Map 和显式摘要仍立即计算、校验。 |
| H4：每次操作新建 PG 物理连接 | 直接 settings 工厂原先使用非池化路径；production runtime 原本已有连接池，不能将问题归于全部生产装配。 | PostgresqlPool 统一池创建；直接 settings 工厂默认 8 个连接、借用等待 3 秒并拥有关闭责任。驱动 Store 接收 DataSource，外部池由调用方关闭；HikariCP 依赖下移数据 Adapter。 |
| H5：批量 N+1 与全量分页 | Repository/Store 缺少批量和分页下推真实。默认回退仅用于小型自定义 Store，生产驱动应覆盖。 | 三驱动覆盖 256 项分块批量。PG 使用 ANY/JDBC batch 与 LIMIT/OFFSET；Mongo 使用 $in/ordered bulk 与 skip/limit；Redis 使用按 bucket/slot 的 MGET 和 pipeline。分页只反序列化页内 payload；Redis 索引仍需扫描排序。 |
| H6：flush 全串行、对象级写回 | 跨独立脏键逐项串行真实；对象级脏追踪是仓库明确的默认设计。 | 默认最多 16 个独立脏键在途，构造参数可调。保留 flush 队列顺序、同键代际、逐项成功确认、失败保留和停机预算；未用聚合 saveAll 清脏，避免部分提交无法精确确认。 |
| H7：每包新建 Cipher/KeySpec | encrypted profile 热路径重复 provider 查找和分配真实；Cipher 不可跨线程共享。 | Context 复用 seal/open 各一个 Cipher 与 SecretKeySpec，每包执行 init/AAD/doFinal；close 清理密钥副本和对象引用。单 EventLoop/单线程归属、认证、nonce 和线格式保持。 |
| H8：共享 reply topic 广播消费 | 独立 consumer group 订阅同一 reply topic 会让每个实例读取全部响应；correlationId 分区键本身无法消除 group 间广播。 | production 所有 reply 配置值均作为前缀，每 runtime 追加 UUID；lifecycle 将实际 request/response 的 replyTopic 重写为本实例 topic，保留其他字段和安全 metadata。直接 KafkaRpcAdapter 仍由调用方保证唯一 topic。 |

## 关键实现与契约

- [Envelope Repository](../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeCrudRepository.java)只把同步驱动 IO 移入注入执行器，未迁移到 Reactive 驱动。调用方仍须在 Actor 所属域生成实体快照，完成回调修改状态前回投 Actor。工厂 close 等待已进入 Store 的操作，尚未进入的排队操作失败；注入的外部资源不由工厂关闭。
- [Store 契约](../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeStore.java)规定 findByIds 按输入顺序、保留重复项、省略缺失项。saveAll/deleteAll 不承诺批次事务原子性。PG 批量 CAS 要求逐项精确更新数，开启 JDBC rewrite 也不接受 SUCCESS_NO_INFO；Mongo 聚合冲突无法定位时整体报告 VERSION_CONFLICT。Redis 检查全部 pipeline 响应，先为全部成功项写 journal 再报告中途失败，批量 journal 每文件 force 一次。
- [持久化管理器](../../zero-data/src/main/java/group/zn/zero/data/persistence/DefaultPersistenceManager.java)以逐项确认换取可跨后端一致处理的脏数据语义。有界并行降低串行 RTT 累加，但不是按 target 的事务批量；跨键版本写入次序不再由全仓库锁决定。
- [SyncEnvelope](../../zero-state-sync/src/main/java/group/zn/zero/statesync/SyncEnvelope.java)的 preparePayload 只接受可深冻结的 Map、Collection 和指定不可变标量；数组、自定义可变叶子被拒绝。该入口可供多观察者复用，但未实现生产广播链路或共享编码字节。
- [Kafka production 路由](../../zero-runtime-kafka/src/main/java/group/zn/zero/runtime/kafka/KafkaRpcLifecycleAdapter.java)由 runtime 拥有。reply 前缀字符集为 `[a-zA-Z0-9._-]+`，最多 216 字符，加后缀后不超过 Kafka 249 字符限制；oneway 保持原样。

## 自动验证

最终质量验证开始于 2026-10-05 12:42:09（Asia/Shanghai），Maven 退出码 0。只统计本次运行新生成的 Surefire XML：252 个 suite、985 项测试，失败 0、错误 0、跳过 0，覆盖 36 个有测试的模块。该数字是相关依赖闭包回归总量，包含工作树既有测试，不是本次新增测试数。

质量工具的本次新报告：Checkstyle 49 份、PMD 48 份、SpotBugs 48 份，报告违规均为 0；SpotBugs 按仓库 High 阈值配置运行。JaCoCo 生成 36 份报告；当前 profile 未配置覆盖率 check，不能声称通过覆盖率阈值。明细、时间和哈希保存在[测试汇总](../../tasks/archive/20261005-performance-issues/quality-surefire.json)及[质量工具汇总](../../tasks/archive/20261005-performance-issues/quality-tools.json)。

```powershell
$env:JAVA_HOME = 'D:/env/jdk21'
$env:Path = 'D:/env/jdk21/bin;' + $env:Path
& D:/env/apache-maven-3.9.8/bin/mvn.cmd -pl zero-runtime-kafka,zero-state-sync,zero-net-kcp,zero-runtime-postgresql,zero-runtime-mongo,zero-runtime-redis,zero-server-starter-production -am verify -Pquality -q
```

关键回归包含 ZeroDataEnvelopeCrudRepositoryTest（10 项）、PersistenceParallelFlushTest（2 项）、SyncPayloadReuseTest（4 项）、KcpProtectionReuseTest（3 项）、KafkaProductionTelemetryObserverTest（5 项）、KafkaReplyRoutingTest（1 项）、PostgresqlConnectionPoolTest（2 项）与 ZeroRuntimeExecutorsFocusedTest（4 项）。它们验证异步提交与拒绝终结、调用域快照、批量/分页委托、有界并行与新标脏、摘要复用和不可变性、Cipher 复用与认证失败恢复、日志筛选、reply 重写及池/执行器生命周期。

## 真实中间件验证

复用 M 盘本机 Docker 服务：Redis 7.4.2-alpine（127.0.0.1:16379）、PostgreSQL 16.4-alpine（127.0.0.1:15432）、MongoDB 7.0.14（127.0.0.1:17017）以及 Kafka 3.9.1（127.0.0.1:29092）。服务资源位于 `M:/docker_space`，复用既有账户与凭据；没有引入新镜像或重置共享数据。

| 外部 suite | 项数 | 实际验证 |
| --- | --- | --- |
| PostgresqlBatchPageExternalIT | 1 | 300 项跨 256 分块，顺序/重复/缺失 ID；rewrite=true 下混合精确 CAS；页外损坏记录不影响目标页；backend_pid 复用及池关闭。 |
| MongoBatchPageExternalIT | 1 | 批量读写、分页；聚合 CAS 冲突的保守失败；ordered bulk 重复插入的部分提交。 |
| RedisBatchPageExternalIT | 2 | 跨 bucket 批量、分页与 CAS；pipeline 中间 WRONGTYPE 时前后成功项均写 journal。 |
| KafkaReplyIsolationExternalIT | 1 | 两个相同 production 配置的 runtime 各 8 次成功往返；独占 reply topic 各 8 条、metadata 验证、payload/计数/pending；成功零传输日志，handler 失败有安全日志。 |

四个外部 suite 共 5 项，失败/错误/跳过均为 0，详见[外部测试汇总](../../tasks/archive/20261005-performance-issues/external-results.json)。Kafka 测试的日志操作名断言按既有 `kafka-rpc-handler_failed` 修正后重跑通过。以下命令可按相同选择复现；先依照本地 ENVIRONMENT.md 注入连接环境变量，密码仅从现有 secret 文件读入进程环境：

```powershell
& D:/env/apache-maven-3.9.8/bin/mvn.cmd -pl zero-data-postgresql,zero-data-mongo,zero-data-redis -am verify -Pexternal-tests '-Dit.test=PostgresqlBatchPageExternalIT,MongoBatchPageExternalIT,RedisBatchPageExternalIT' '-Dfailsafe.failIfNoSpecifiedTests=false' -q
& D:/env/apache-maven-3.9.8/bin/mvn.cmd -pl zero-server-starter-production -am verify -Pexternal-tests '-Dit.test=KafkaReplyIsolationExternalIT' '-Dfailsafe.failIfNoSpecifiedTests=false' -q
```

所需变量为 ZERO_POSTGRESQL_URL、ZERO_POSTGRES_USER、ZERO_POSTGRES_PASSWORD、ZERO_MONGO_URI、ZERO_REDIS_URI 和 ZERO_KAFKA_BOOTSTRAP_SERVERS。测试使用自己的表/库/namespace/topic 并清理自身数据。完成后仅 scoped stop 本次启动的四项服务，全部核验为 exited；hardening 项目的另一 Redis 维持原停止状态。Kafka 的退出码 143 来自 stop 发出的终止信号，不是测试退出码。状态见[恢复记录](../../tasks/archive/20261005-performance-issues/docker-restored.json)。

## API 与架构检查

- 架构守卫通过：61 模块、21 条规则，违规/警告 0；它是静态边界检查。
- API 消费者编译通过：5 模块，Java 21；仅覆盖脚本中的小型公共契约消费者。
- 公共符号检查：zero-core、zero-protocol、zero-rpc-common 和本次 zero-data 基线通过。zero-data 仅审阅调整 13 项已有符号（两个构造器、inMemory、十个 CRUD synchronized 标记），没有全量重新生成基线。
- 全仓符号脚本退出码 1：zero-runtime 仍有 3 项既有基线差异，分别为 DefaultGameRuntime 的 start/stop/close 取消全方法 synchronized。该实现变化已由[上一轮反馈任务](feedback-hardening-20261004.zh-CN.md)记录，本次没有修改该类或扩展修改其基线。不能将全仓 API 门禁报告为通过；详见[API 日志](../../tasks/archive/20261005-performance-issues/api-check.log)。

复现命令为 `java scripts/VerifyApiCompatibilityConsumer.java`、`java scripts/ZeroArchitectureGuard.java` 和 `java scripts/VerifyPublicApiCompatibility.java --check`。编译前执行了五个受保护模块及依赖的 `mvn -DskipTests ... install`，详见[任务验证记录](../../tasks/archive/20261005-performance-issues/VERIFY.md)。

## 剩余边界

本次证明控制流、数据语义和真实驱动往返正确，没有测量 10k RPC/s、数万 KCP pps、p99、长稳容量或一万脏实体的 30 秒停机 SLA，也未给出吞吐提升百分比。Redis Cluster、多 broker/副本故障转移和生产 ACL/provisioning 未验证。

Redis 分页仍扫描并排序 ID 索引，沿用 100000 预算；PG/Mongo offset 深分页与 count 仍可能较昂贵，total 与 items 弱一致。自定义 Store 默认方法仍有逐项/全量回退。remote IO 虚拟线程不提供业务在途上限，调用方须按驱动池容量和超时预算控制准入。异常日志仍同步；Kafka 独占 reply topic 需要前缀 ACL、provisioning、retention 和历史 topic 清理。以上限制已写入迁移及主题文档。
