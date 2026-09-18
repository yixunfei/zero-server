# 数据与缓存设计

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
- Redis 故障：`LayeredCacheService` 标记 degraded，保留未过期 L1 和 loader 回源能力，并暴露 backend failure、backlog 与 write-back failure 计数。
- 本地内存膨胀：`CachePolicy` 提供 L1 最大条目数，首版使用轻量近似淘汰。

分布式缓存常见模型：

- cache-aside：S2C-03 默认模型，适合大多数读多写少业务缓存。
- read-through：可由 `getOrLoad` + Repository loader 表达，具体 loader 由业务或 starter 装配。
- write-through：不默认开启，可由业务在 Repository save 成功后调用 `putVersioned` 显式刷新。
- write-back：不默认启用，仍应通过 `PersistenceManager` 做脏对象登记、线程绑定快照和定时 flush。
- 强一致对象：充值订单、账号、货币等默认不走自动缓存写回，必须由业务显式选择事务、版本或补偿策略。

## 6. 玩家在线数据

玩家在线数据常驻内存。

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
- flush 成功后移除脏对象，失败时保留脏对象并统计失败次数，等待下一轮重试。
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

- `zero-data` 当前提供 `VersionedEntity`、`PageRequest`、`PageResult`、`Repository`、`CrudRepository`、`InMemoryCrudRepository` 和 `AbstractRepositoryAdapter`。
- `zero-data` 当前提供 `group.zn.zero.data.mapping` 映射基础，包括 `ZeroDataObject`、`ZeroDataId`、`ZeroDataVersion`、`ZeroDataField`、`ZeroDataCompositeKey`、`ZeroDataKeyPart`、`ZeroDataReference`、`ZeroDataReferenceList`、`ZeroDataOwnedCollection`、`ZeroDataEmbedded`、`ZeroDataIgnore`、`ZeroDataKeyCodec`、`DefaultZeroDataKeyCodec`、`ZeroDataKeyGenerator`、`UuidZeroDataKeyGenerator` 和 `ZeroDataMappingIntrospector`。
- `zero-data` 当前提供 `group.zn.zero.data.envelope` 信封基础，包括 `ZeroDataEnvelope`、`ZeroDataEnvelopeCodec`、`ZeroDataEntityCodec`、`ZeroDataEnvelopeStore` 和 `ZeroDataEnvelopeCrudRepository`；业务对象 payload 通过 `zero-protocol` 的 `ZeroPayloadCodec` / `ZeroWriter` / `ZeroReader` 编码。`ZeroDataEnvelopeStore#saveIfVersion` 是真实后端乐观锁原子写入口，Repository 不再把版本判断停留在先读后写的 Java 层。
- `zero-data` 当前提供 `group.zn.zero.data.persistence` 持久化管理基础，包括 `PersistenceManager`、`DefaultPersistenceManager`、`DataThreadBinding`、`PersistenceBindingExecutor`、`PersistenceScheduler`、`PersistenceTarget`、`PersistenceFlushResult` 和 `PersistenceStatistics`。
- `InMemoryCrudRepository` 支持版本递增、批量保存、批量查询、分页和快照；版本冲突绑定 `DataErrorCode.VERSION_CONFLICT`。
- `zero-data-mongo`、`zero-data-redis` 和 `zero-data-postgresql` 当前均通过 `AbstractRepositoryAdapter` 暴露可切换的仓库边界，并可注册基于 zcode envelope 的最小 Repository。
- `zero-data-mongo` 当前提供 `MongoDataDocument`、`MongoDataEnvelopeStore`、`MongoDriverEnvelopeStore`、`MongoDriverSettings` 和 `MongoDataHealthCheck`，以 document 形态保存 `_id`、版本、schema、codec、更新时间和 zcode payload；driver-backed store 使用 namespaced 物理 collection 与版本条件 replace，避免跨 namespace 覆盖和并发丢更新；连接串和数据库名称可由系统属性或 `ZERO_MONGO_URI` / `ZERO_MONGO_DATABASE` 环境变量注入。
- `zero-cache` 当前提供 `CacheEntry`、`CacheLoader`、`CachePolicy`、`CacheStore`、`CacheStoreEntry`、`CacheKeyCodec`、`CacheKeyCodecs`、`CacheValueCodec`、`CacheHealthSnapshot`、`CacheLoadCoordinator`、`LayeredCacheService`、`CacheStatistics` 和 `InMemoryCacheService`；`LayeredCacheService` 承载 L1 + L2 + loader 的 cache-aside 编排。
- `zero-data-redis` 当前提供 `RedisDataKeyStrategy` 和 `DefaultRedisDataKeyStrategy`，用于内部生成对象快照、bucket 索引和追加日志 key；`RedisDataEnvelopeStore` 会维护 snapshot、bucket index、PUT/DELETE journal，`RedisDriverEnvelopeStore` 可通过 Jedis 写入真实 Redis，并用 Lua 脚本一次完成版本 CAS、snapshot、index 和 Redis journal。`LocalDiskDataJournal` 使用 zcode entry 追加本地 zlog，作为 Redis 写失败时的降级入口。
- `zero-data-redis` 当前还提供 `RedisCacheKeyStrategy`、`DefaultRedisCacheKeyStrategy`、`RedisCacheEnvelope`、`RedisCacheEnvelopeCodec`、`RedisCacheStore`、`RedisCacheHealthCheck` 和 `RedisDistributedCacheService`，使用独立 `zero:cache` / `zero:cachever` / `zero:cacheidx` key namespace 承载 Redis L2 缓存，避免与 snapshot / bucket index / journal key 混用，并可显式探测缓存后端健康状态。
- `zero-data-postgresql` 当前提供 `PostgresqlDataRow`、`PostgresqlDataEnvelopeStore`、`PostgresqlDriverEnvelopeStore`、`PostgresqlDriverSettings` 和 `PostgresqlDataHealthCheck`，以通用对象表行形态保存 namespace、collection、id、版本、schema、codec、更新时间和 zcode payload；driver-backed store 使用版本条件 update 承载乐观锁，连接 URL、用户名和密码必须由系统属性或环境变量注入。
- 当前实现已具备 MongoDB / Redis / PostgreSQL 真实驱动外部集成测试入口；默认单元测试仍不强制依赖外部服务。
- `docs/operations/evidence/repository-save-performance-evidence.zh-CN.md` 已整理 envelope、Repository CAS、版本冲突、saveAll、dirty flush、失败保留和三类 Adapter 分层测量口径；该入口不创建 Store、不启动调度、不连接数据库，也不证明生产容量。
- `docs/operations/evidence/cache-get-or-load-performance-evidence.zh-CN.md` 已整理 L1/L2、loader、singleflight、负缓存、并发加载背压、L2 故障降级和 Redis Driver 分层测量口径；该入口不创建 Cache、不调用 loader、不启动线程、不连接 Redis，也不把聚合 hit 计数误作 L1/L2 分层证据。


## 2026-09-17 报告核实修订

内存缓存先登记单飞 future 再调用加载器，完成时按身份移除；显式写入和失效阻止更早加载回填。L2 回填 L1 原子比较缓存版本并保留原到期时间；读取到的 L2 版本推进本地版本生成器。持久化管理器必须先 start；在仓库及快照执行域关闭前 stop，停止期间拒绝新增脏对象，等待在途 flush 并保存全部剩余对象。默认停机预算 30 秒，可通过构造器配置；依赖返回非阻塞 CompletionStage。失败/超时保留脏入口并以 PERSISTENCE_FLUSH_FAILED 使生命周期进入 FAILED，可恢复依赖后再次 stop。并发 flush 合并为同一在途批次，后续脏入口继续保留。
