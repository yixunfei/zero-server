# 数据与缓存设计

2026-10-11：Redis/MongoDB/PostgreSQL 原生运行 IO 使用各自 operation-timeout-millis，不再复用启动超时。Redis data/cache 共享运行预算及 READINESS；恢复快照与未知写结果处理见[统一配置指南](guides/configuration-and-catalog.zh-CN.md)。

## 2026-10-10 运行时远程 IO 恢复

Production Redis 数据与缓存共用 client owner 和健康/准入状态；MongoDB、PostgreSQL 也通过运行时监测驱动 IO。已知连接或超时故障触发降级，周期探测成功后恢复准入；容量拒绝不触发写入。可能已发送的写入返回 `WRITE_OUTCOME_UNKNOWN`，不自动重试，业务需通过版本/请求 ID 或对账确认结果。SQL 完整性约束等确定性错误保留原业务失败，不触发远程故障降级。驱动原生超时仍是实际阻塞上限；Actor/Netty 路径应使用现有受管异步 Repository/Cache 入口，不能直接调用同步驱动接口。

## 2026-10-05 Repository 与持久化性能

Envelope CRUD 不再持有仓库级同步锁，所有同步 Store IO 交给必填的受管 Executor；保存前在实体所属调用域编码不可变 envelope，查询解码及完成回调可能在 IO 域执行。production 数据 provider 拒绝内联执行器，便利工厂默认提供 Java 21 虚拟线程 remote IO。自定义 Store/codec 必须支持并发，业务仍需按驱动容量限制在途请求。

批量查询、CAS 写入、删除与分页已下推至三类生产驱动。PG/Mongo 仅读取页内 payload；Redis 页内 MGET 前仍扫描并排序索引，最多 100000 项。批次可能部分提交，不承诺跨后端事务原子性。`LocalDiskDataJournal.appendAll` 每个文件只 force 一次，单项 flush 仍可能逐项 force。

直接 PostgreSQL settings 工厂使用默认最大 8 个连接的 HikariCP，工厂关闭自有池；外部 DataSource 和 IO executor 保留调用方所有权。`DefaultPersistenceManager` 默认最多 16 个独立脏键同时保存，可配置并发预算；逐项成功才清除 dirty，保持新标脏代际和失败保留。对象级脏追踪仍是设计契约。API、关闭顺序及部分提交迁移见[说明](migrations/20261005-performance-issues.zh-CN.md)。

## 2026-10-04 反馈专项边界

Redis cache store 支持显式有界 IO executor；Envelope repository 现强制注入 executor，不再提供通用池兼容入口。生产装配应把 Jedis/Mongo/PG 阻塞调用放入远程 IO 执行域；调用方不得在 Actor、Netty 或 Kafka poll 线程直接使用同步 driver。cache 的旧兼容入口仍需迁移为显式 executor，生产禁止内联 IO。

`LayeredCacheService` 可配置 L2 故障冷却窗口。旧构造器保持 `Duration.ZERO` 的历史 miss 回源行为；显式设置正数窗口后，Redis 读取连续失败会在冷却期快速失败，避免数据库被全量穿透。冷却不是数据一致性保证，必须结合 loader、L1 保留、失败计数和业务降级策略评估容量。

`DirectPersistenceBindingExecutor` 仍要求 snapshot supplier 在对象所属线程绑定域读取不可变快照；它不会替业务把 live mutable 对象安全地复制到 flush 线程。Mongo/PG 直接构造的驱动操作超时仍需应用显式配置。journal 批量 force 已实现，管理器按逐项确认保存时仍未统一使用批量 journal；历史风险见[反馈专项报告](reports/feedback-hardening-20261004.zh-CN.md)。

## 2026-10-03 物理写顺序与 fencing

分层缓存的已接受写入保持物理完成顺序，调用方取消或读超时不提前解除写入屏障。每键最多 1024、实例最多 4096 个未结算写入，超限以 `BACKPRESSURE_REJECTED` 拒绝；内存 loader 落实现有 maxConcurrentLoads。写回观察 Future 取消不阻止内部重试状态归还。

Redis 未版本化值以 cacheVersion 条件覆盖；正实体版本继续按 entityVersion fencing，原存储格式不变。PostgreSQL ownership release 保留 epoch 历史，并发相同提交在取得行锁后重新查幂等收据。内存 fenced state 只允许组合 InMemoryOwnershipStore，验证和写入在同一原子临界区完成；远程场景使用对应事务存储。完整行为收紧和验证见[迁移说明](migrations/20261003-concurrency-async-performance.zh-CN.md)。

## 2026-09-28 报告核实补充

- `RedisDataEnvelopeStore` 是明确的内存 prototype，不提供重启持久化或多实例共享；生产装配必须使用 `RedisDataAdapter.registerDriverRepository` 注入真实 `RedisClient`。需要本地原型时显式使用 `registerInMemoryZcodeRepository`。
- PostgreSQL 条件写按 SQLState 区分版本冲突、并发冲突、后端不可用和一般写失败；Mongo 条件写按 DuplicateKey、瞬态和网络异常分类。调用方不得把所有 `WRITE_FAILED` 当成版本冲突重试。
- Mongo 全量读取有 `maxFindAll` 上限，超过上限返回 `READ_LIMIT_EXCEEDED`；大集合请使用分页。三类驱动的 `saveAll/findByIds` 已覆盖批量协议，框架不承诺跨后端事务或批次全有全无。
- `DefaultPersistenceManager` 的每次 flush 有独立 future，脏对象登记有界；写回失败由 `LayeredCacheService.retryWriteBacks` 提供有界重试。L2 为空是纯本地缓存的显式选择，不等同于分布式一致性。

## 2026-09-26 并发安全补充

分层缓存的每次读取都绑定读取代际。`invalidate`、版本化写入或显式突变会推进该代际；迟到的 L2/loader 回填只有在代际仍匹配且当前条目未被更新时才能写入 L1，因此超时请求不会复活已经失效或被新版本替换的值。singleflight 的 action stage 会继承调用方取消信号，取消只释放本次等待者，不会无条件取消仍被其他等待者共享的加载。

版本条件失效、写回和 Redis 条件脚本仍是跨实例一致性的边界：旧版本不能删除或覆盖新版本，Redis 失败必须显式报告并保留可恢复现场。缓存命中不是事实来源，强一致对象必须继续使用 Repository 事务、版本或补偿策略。相关回归覆盖见[专项审查报告](reports/concurrency-distributed-audit-20260926.zh-CN.md)。

2026-09-22：Redis 条件写异常不再通过本地日志伪装成功，删除使用原子脚本；本地日志追加强制刷盘并恢复尾部不完整帧。
缓存条件失效按实体版本保护 L1 并发更新，同正实体版本不覆盖；本地容量按插入顺序淘汰，加载默认 3 秒超时。
详见[行为与 API 迁移](migrations/20260922-security-storage-concurrency.md)。

## 1. 存储职责

默认职责：

- PostgreSQL：平台、账号、后台、渠道、服务器基础信息。
- MongoDB：游戏业务主数据。
- Redis：缓存、排行榜、短期状态、追加式持久化过渡方案。

Redis 追加式持久化用于不引入 MongoDB 时的临时过渡。MongoDB 与 Redis 追加式存储需要共享同一套对象映射注解。

## 2. 数据访问抽象

框架提供统一：

- `Repository`
- `DataService`

目标：

- 屏蔽具体数据库。
- 支持 MongoDB 与 Redis 落地切换。
- 支持数据变更事件。
- 支持审计。
- 支持日志与数据中台转发。

业务应通过统一数据模块访问存储。框架仍需考虑非正常直接访问中间件导致的数据一致性风险。

## 3. MongoDB 组织

MongoDB 数据默认可按以下集合拆分：

- 玩家。
- 实体。
- 场景。
- 活动。

具体集合策略由业务决定。

## 4. Redis 追加式持久化

Redis 追加式持久化采用类似 event-sourcing/log-structured storage 的设计。

目标：

- 数据按对象形式存储。
- 基于 ID 等关键字段编码。
- 加载时按对象完整加载。
- 尽量达到与 MongoDB 类似的对象访问兼容程度。

## 5. 缓存策略

需要支持：

- 本地缓存。
- Redis 分布式缓存。
- 自动加载。
- 写回。
- 失效。
- 版本号。
- 防击穿。
- 防穿透。

S2C-03 首版采用 L1 + L2 cache-aside：

```text
CacheService / LayeredCacheService
  -> L1 InMemoryCacheService
  -> L2 CacheStore SPI
  -> zero-data-redis RedisCacheStore
```

默认读路径：

- 先读本地 L1，命中直接返回。
- L1 未命中后读 Redis L2，命中后回填 L1。
- L1 / L2 均未命中时通过 per-key singleflight 调用 loader 回源。
- loader 返回空值时写入短 TTL 负缓存，降低穿透风险。

默认写路径：

- Repository 写入成功后由业务显式 `invalidate` 或 `putVersioned`。
- Redis L2 使用独立 cache envelope，不复用数据持久化 envelope。
- Redis L2 使用 Lua 脚本完成版本条件写入和条件失效，避免旧版本覆盖或旧请求删除新缓存。
- Redis cache key 默认使用 `CacheKeyCodec` 规范化，不再使用裸 `String.valueOf(key)`。
- 当前 key 格式为 `k1:<type>:<payload>`，支持 String、Integer、Long、UUID、Enum 和 Java record 组合 key；不支持的普通对象默认 fail-fast。
- S2C-03 cache key 规范化采用方案 A 直接切换，不做旧 Redis cache key 双读兼容；已有缓存允许冷启动。

缓存常见问题处理：

- 击穿：`CacheLoadCoordinator` 按 key singleflight，限制最大并发加载数。
- 穿透：负缓存保存空结果，并使用较短 TTL。
- 雪崩：`CachePolicy` 支持 TTL jitter，避免大量 key 同时过期。
- Redis 故障：`LayeredCacheService` 标记 degraded，保留未过期 L1 和 loader 回源能力，并暴露 backend failure、backlog 与 write-back failure 计数。loader 结果写 L2 失败时默认不回填 L1（含负缓存），当前调用仍返回 loader 结果；只有显式本地降级选项允许失败回填。
- 本地内存膨胀：`CachePolicy` 提供 L1 最大条目数，首版使用轻量近似淘汰。

分布式缓存常见模型：

- cache-aside：S2C-03 默认模型，适合大多数读多写少业务缓存。
- read-through：可由 `getOrLoad` + Repository loader 表达，具体 loader 由业务或 starter 装配。
- write-through：不默认开启，可由业务在 Repository save 成功后调用 `putVersioned` 显式刷新。
- write-back：不默认启用，仍应通过 `PersistenceManager` 做脏对象登记、线程绑定快照和定时 flush。
- 强一致对象：充值订单、账号、货币等默认不走自动缓存写回，必须由业务显式选择事务、版本或补偿策略。

## 6. 玩家在线数据

玩家在线数据常驻内存。

`LocalPlayerService` 默认使用 `MissingPlayerPolicy.REJECT`，仓库未命中返回 `PLAYER_NOT_FOUND`，不自动建档。登录建立会话前查询权威仓库；认证和账号对 UID 的授权仍由业务负责。原型或明确需要自动建档的业务可通过构造函数选择 `CREATE_DEFAULT`。普通加载可以读缓存，删档需同步失效缓存。

保存策略：

- 定时异步落库。
- 数据存储线程绑定。
- 对象级脏数据追踪。
- 成功落库后才允许自动清理离线玩家数据。

持续落库失败时：

- 保持缓存。
- 更新服务器状态。
- 降级服务。
- 保留原始现场。
- 尽可能生成内存快照。
- 防止项目内存进一步恶化。

## 7. 统一持久化管理

`zero-data` 提供 `group.zn.zero.data.persistence` 作为首版统一持久化管理入口。

职责：

- 通过 `PersistenceManager` 登记持久化目标和脏对象。
- 通过 `DataThreadBinding` 描述对象应在哪个逻辑执行域捕获快照。
- 通过 `PersistenceBindingExecutor` 在绑定执行域内捕获可落库快照，避免持久化线程直接读取 Actor 内 live mutable 对象。
- 通过 `PersistenceScheduler` 接入定时 flush；生产调度器应由 starter 或后续统一线程管理提供，`zero-data` 不直接创建生产线程池。
- flush 成功后按 key 与 DirtyEntry 条件相等删除；每次标脏有独立 generation，因此 flush 期间的新标脏（即使 supplier 相同）会保留。失败时保留脏对象并统计失败次数，等待下一轮重试。
- 为后续缓存写回、失败降级和积压观测保留统一入口，但不在 `S2C-02` 中实现 Redis 分布式缓存策略。

首版边界：

- `zero-data` 不依赖 `zero-actor`；后续由 actor/starter adapter 将 `DataThreadBinding` 映射到 `LaneKey`。
- 持久化对象内容仍通过 Repository 和 zcode envelope 落库，不绕过 Adapter。
- 定时落库只调度 flush 流程，线程池、背压、限流和生产恢复策略后续在统一线程管理或 starter 装配中细化。

## 8. 强一致数据

强一致重点对象：

- 充值订单。
- 账号。
- 货币。

具体实现按业务场景选择事务、锁、版本号或补偿机制。

## 9. 当前实现快照

## 10. 数据库兼容与性能审查边界

2026-09-26 的专项审查确认三类 driver 均通过统一 envelope 和版本条件写边界工作：MongoDB 使用 namespaced collection 与条件 replace，Redis 使用独立 key namespace 和原子脚本，PostgreSQL 使用参数化 SQL、受校验的表名和版本条件 update。真实验证只覆盖本机单实例 MongoDB 7.0、Redis 7.4 和 PostgreSQL 16；不等同于集群故障转移、跨版本兼容、容量或 SLA 证明。

当前容量观察项：Envelope Store IO 已移出调用线程且无仓库级串行锁，三类驱动覆盖分块批量与分页。PG/Mongo 页内读取避免全量反序列化；Redis 仍需有界索引扫描和排序。PostgreSQL settings 默认工厂与 production 均使用 HikariCP。批量 CAS、失败保留和真实连接复用已有专项集成测试，业务吞吐、尾延迟、深 OFFSET、连接池饱和及长稳仍应使用真实 workload 测量。自定义 Store 的默认批量/分页回退仅适合小型数据集。

Redis 数据与缓存 key 的 namespace/collection/cacheName 不得包含 `{`、`}` 或 `:`，避免 hash-tag 边界和分隔符造成跨空间别名；本地 journal 路径片段拒绝控制字符、路径分隔符、点段和 Windows 特殊文件名字符。

本轮还修复了分页结束位置的 `int` 加法溢出。超大 `offset + limit` 现在按 `long` 计算后裁剪到结果大小，避免非法 `subList` 范围；正常分页结果和游标格式保持不变。

- `zero-data` 当前提供 `VersionedEntity`、`PageRequest`、`PageResult`、`Repository`、`CrudRepository`、`InMemoryCrudRepository` 和 `AbstractRepositoryAdapter`。
- `zero-data` 当前提供 `group.zn.zero.data.mapping` 映射基础，包括 `ZeroDataObject`、`ZeroDataId`、`ZeroDataVersion`、`ZeroDataField`、`ZeroDataCompositeKey`、`ZeroDataKeyPart`、`ZeroDataReference`、`ZeroDataReferenceList`、`ZeroDataOwnedCollection`、`ZeroDataEmbedded`、`ZeroDataIgnore`、`ZeroDataKeyCodec`、`DefaultZeroDataKeyCodec`、`ZeroDataKeyGenerator`、`UuidZeroDataKeyGenerator` 和 `ZeroDataMappingIntrospector`。
- `zero-data` 当前提供 `group.zn.zero.data.envelope` 信封基础，包括 `ZeroDataEnvelope`、`ZeroDataEnvelopeCodec`、`ZeroDataEntityCodec`、`ZeroDataEnvelopeStore` 和 `ZeroDataEnvelopeCrudRepository`；业务对象 payload 通过 `zero-protocol` 的 `ZeroPayloadCodec` / `ZeroWriter` / `ZeroReader` 编码。`ZeroDataEnvelopeStore#saveIfVersion` 是真实后端乐观锁原子写入口，Repository 不再把版本判断停留在先读后写的 Java 层。
- `zero-data` 当前提供 `group.zn.zero.data.persistence` 持久化管理基础，包括 `PersistenceManager`、`DefaultPersistenceManager`、`DataThreadBinding`、`PersistenceBindingExecutor`、`PersistenceScheduler`、`PersistenceTarget`、`PersistenceFlushResult` 和 `PersistenceStatistics`。
- `InMemoryCrudRepository` 支持版本递增、批量保存、批量查询、分页和快照；版本冲突绑定 `DataErrorCode.VERSION_CONFLICT`。
- `zero-data-mongo`、`zero-data-redis` 和 `zero-data-postgresql` 当前均通过 `AbstractRepositoryAdapter` 暴露可切换的仓库边界，并可注册基于 zcode envelope 的最小 Repository。
- `zero-data-mongo` 当前提供 `MongoDataDocument`、`MongoDataEnvelopeStore`、`MongoDriverEnvelopeStore`、`MongoDriverSettings` 和 `MongoDataHealthCheck`，以 document 形态保存 `_id`、版本、schema、codec、更新时间和 zcode payload；driver-backed store 使用 namespaced 物理 collection 与版本条件 replace，避免跨 namespace 覆盖和并发丢更新；连接串和数据库名称可由系统属性或 `ZERO_MONGO_URI` / `ZERO_MONGO_DATABASE` 环境变量注入。
- `zero-cache` 当前提供 `CacheEntry`、`CacheLoader`、`CachePolicy`、`CacheStore`、`CacheStoreEntry`、`CacheKeyCodec`、`CacheKeyCodecs`、`CacheValueCodec`、`CacheHealthSnapshot`、`CacheLoadCoordinator`、`LayeredCacheService`、`CacheStatistics` 和 `InMemoryCacheService`；`LayeredCacheService` 承载 L1 + L2 + loader 的 cache-aside 编排。
- `zero-data-redis` 当前提供 `RedisDataKeyStrategy` 和 `DefaultRedisDataKeyStrategy`，用于内部生成对象快照、bucket 索引和追加日志 key；`RedisDataEnvelopeStore` 会维护 snapshot、bucket index、PUT/DELETE journal，`RedisDriverEnvelopeStore` 可通过 Jedis 写入真实 Redis，并用 Lua 脚本一次完成版本 CAS、snapshot、index 和 Redis journal。`LocalDiskDataJournal` 使用 zcode entry 追加本地 zlog，仅在 Redis 成功写入后记录镜像，不是提交前 WAL，失败不宣称双介质原子提交。
- `zero-data-redis` 当前还提供 `RedisCacheKeyStrategy`、`DefaultRedisCacheKeyStrategy`、`RedisCacheEnvelope`、`RedisCacheEnvelopeCodec`、`RedisCacheStore`、`RedisCacheHealthCheck` 和 `RedisDistributedCacheService`，使用独立 `zero:cache` / `zero:cachever` / `zero:cacheidx` key namespace 承载 Redis L2 缓存，避免与 snapshot / bucket index / journal key 混用，并可显式探测缓存后端健康状态。
- `zero-data-postgresql` 当前提供 `PostgresqlDataRow`、`PostgresqlDataEnvelopeStore`、`PostgresqlDriverEnvelopeStore`、`PostgresqlDriverSettings` 和 `PostgresqlDataHealthCheck`，以通用对象表行形态保存 namespace、collection、id、版本、schema、codec、更新时间和 zcode payload；driver-backed store 使用版本条件 update 承载乐观锁，连接 URL、用户名和密码必须由系统属性或环境变量注入。
- 当前实现已具备 MongoDB / Redis / PostgreSQL 真实驱动外部集成测试入口；默认单元测试仍不强制依赖外部服务。
- `docs/operations/evidence/repository-save-performance-evidence.zh-CN.md` 已整理 envelope、Repository CAS、版本冲突、saveAll、dirty flush、失败保留和三类 Adapter 分层测量口径；该入口不创建 Store、不启动调度、不连接数据库，也不证明生产容量。
- `docs/operations/evidence/cache-get-or-load-performance-evidence.zh-CN.md` 已整理 L1/L2、loader、singleflight、负缓存、并发加载背压、L2 故障降级和 Redis Driver 分层测量口径；该入口不创建 Cache、不调用 loader、不启动线程、不连接 Redis，也不把聚合 hit 计数误作 L1/L2 分层证据。


## 2026-09-17 报告核实修订

内存缓存先登记单飞 future 再调用加载器，完成时按身份移除；显式写入和失效阻止更早加载回填。L2 回填 L1 原子比较缓存版本并保留原到期时间；读取到的 L2 版本推进本地版本生成器。持久化管理器必须先 start；在仓库及快照执行域关闭前 stop，停止期间拒绝新增脏对象，等待在途 flush 并保存全部剩余对象。默认停机预算 30 秒，可通过构造器配置；依赖返回非阻塞 CompletionStage。失败/超时保留脏入口并以 PERSISTENCE_FLUSH_FAILED 使生命周期进入 FAILED，可恢复依赖后再次 stop。并发 flush 合并为同一在途批次，后续脏入口继续保留。

## 2026-09-28 存储与降级边界

ZeroDataEnvelopeStore.saveIfVersion 的默认实现返回 ATOMIC_WRITE_UNSUPPORTED 异常，不再先读后写；自定义实现必须提供真正原子的条件写。ScopedEnvelopeStore 保留原始 cause 链，外层维持稳定数据错误码及文案。

PostgreSQL / MongoDB driver 直接 save 拒绝低于后端当前版本的信封，等版本仍允许替换。直接 save 不是 CAS；并发修改必须走 Repository / saveIfVersion，修复旧业务内容也应使用新的版本号。

LayeredCacheService(policy, store, true) 与 RedisDistributedCacheService(name, policy, store, true) 显式开启 L2 写失败后的本地降级，允许暂存 loader 正值及负结果。默认关闭；当前调用仍取得 loader 结果，写回重试队列仍有界、非持久、无内建后台线程。原有未过期 L1 继续可读，不能据此宣称强一致。

完整默认变化、业务选择和验证见 [迁移说明](migrations/20260928-report-audit-followup.md)。


## 2026-10-01 重试与耐久确认

普通 `markDirty` 继续是内存准入。失败默认最多 5 次、指数退避，版本冲突等立即隔离；失败项保留并占用容量，通过 `failures()` 诊断、`retryFailed()` 显式恢复。成功移除对应脏代际。停机无法排空时明确失败。

需要 JVM 崩溃后恢复已确认修改时，显式使用 `DurablePersistenceService.submit`：绑定域捕获不可变信封，受管 IO 写入并 force 意图后返回 PENDING，后台 CAS 成功且结果刷盘后为 APPLIED。未知结果核验完整快照，冲突隔离。`DurablePersistenceRuntime.module` 管理周期重放及关闭；targets 由应用拥有。

本地日志默认 256 MiB/100000 个操作，完成收据仍占预算；compact 不删除收据。日志满额拒绝，不丢弃未解决数据。磁盘损失、未提交内存与跨机器复制不在保证内。Redis 的成功后镜像不替代该提交协议。

生产 Redis cache 每秒最多重试 128 个写回项，队列按键合并、满额拒绝并计数，原代际防止 invalidate 后旧值复活。核心 Cache 仍无自建后台线程，手动构造者须装配调度。未版本化 put 先观察 L2 版本，最多 3 次冲突重试；不能覆盖 entity-versioned 值。

完整接入、0.x API 变更与报告误判说明见 [可靠性迁移](migrations/20261001-audit-reliability.zh-CN.md)。
