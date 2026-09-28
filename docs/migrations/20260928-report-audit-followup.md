# 2026-09-28 九项报告复核迁移说明

适用版本：0.1.0-SNAPSHOT / 0.x 开发阶段。修改前备份为 `0a00eeb`（分支 `zero/report-audit-remediation`），包含当时所有未提交代码。本次不改变协议线格式、数据库 schema 或模块依赖方向。

## 1. 缺失玩家策略

`LocalPlayerService` 默认采用 `MissingPlayerPolicy.REJECT`。仓库未命中时，加载失败为 `PLAYER_NOT_FOUND`，不自动创建 `player-<uid>`。登录在建立账号会话前查询权威仓库；缓存里的旧档案不能单独证明该 UID 已注册。

- 正式注册流程：先通过业务 Repository 建档，再登录；继续使用带 Repository / Cache 的构造函数即可。
- 本地原型或明确允许自动建档的业务：使用三参数构造 `new LocalPlayerService(scheduler, resolver, MissingPlayerPolicy.CREATE_DEFAULT)`，或五参数构造将同一策略传给自定义 Repository / Cache。
- `withStableHashUid(scheduler)` 同样默认拒绝缺失档案；原型显式调用 `withStableHashUid(scheduler, MissingPlayerPolicy.CREATE_DEFAULT)`。
- 同时建档的版本冲突会读取获胜记录，不覆盖已经保存的玩家；其他仓库失败继续向上传播。
- RPG 示例和 local-game 脚手架已显式选择 `CREATE_DEFAULT`，保留最小原型开箱即用行为。

UID 解析器仍由业务提供。档案存在校验不等于密码、令牌校验或账号对 UID 的授权。普通加载仍可读缓存，业务删档时须同步失效缓存。

## 2. 缓存后端失败

`LayeredCacheService(policy, store)` 在 loader 结果写 L2 失败时，默认不回填 L1，正缓存和负缓存均适用。当前调用仍获得 loader 结果，服务记录降级和有界写回积压；后续读取重新访问 L2。既有未过期 L1 不因此被全局清空。

允许故障期间本地暂存的业务可显式选择：

~~~java
new LayeredCacheService<>(policy, store, true);
new RedisDistributedCacheService<>("redis", policy, redisStore, true);
~~~

该选项允许暂存负结果，需接受本地旧值或负缓存遮蔽 L2 更新直到 TTL / 失效。L2 明确拒绝旧版本时，任何选项均不回填。本次不将缓存写回队列升级为持久消息日志，也不创建后台线程；重试和限流仍由业务装配。

## 3. 原子存储 SPI 和直接保存

`ZeroDataEnvelopeStore.saveIfVersion` 的默认实现立即抛出 `ATOMIC_WRITE_UNSUPPORTED`，不执行读写。自定义存储必须实现后端原子条件写：期望版本 0 仅创建不存在的对象，正数仅更新当前版本匹配的对象；版本条件不满足返回 false。跨实例存储不能用 Java 对象锁加先读后写代替后端原子操作。

现有框架存储实现已经覆盖 CAS；自定义实现和测试替身需要补齐。正常 Repository 路径继续使用 CAS。

PostgreSQL / MongoDB driver 的直接 `save` 改为不允许版本倒退的 upsert：低于当前版本时返回 `VERSION_CONFLICT`，等版本仍可替换 payload，更高版本可写入。此入口不具备完整 CAS 语义；并发 read-modify-write 必须使用 Repository 或 `saveIfVersion`。修复、回放旧业务内容时应基于最新版本条件写入新版本，不再直接塞回较低版本信封。底层直接保存能力需按所选 Adapter 契约使用，不能推导出跨后端事务或统一回放能力。

数据访问包装器保留原始 cause 链，外层消息仍使用稳定的 DataErrorCode 文案。内部诊断可检查连接、约束和超时异常；对客户端输出时不要序列化内部 cause 中的连接信息。

## 4. 房间提交与历史

房间事件进入历史并交给消费者后，消费者异常会传播，但已提交的成员、状态、序号和统计不再回滚。业务校验失败仍保持原状态。出现通知失败时先查询提交结果；加入和相同结算键可按原幂等契约重试，开局等操作不能推定为未执行，更不能盲目重发外部奖励。

默认历史容量仍为 1024，可通过 `LocalRoomService(scheduler, consumer, capacity)` 显式调整。容量约束的是内存历史，不是实时通知。

`eventHistory(roomId, afterSequence)` 原子返回请求序号之后仍保留的事件、最早/最新序号、淘汰数和 gap 标志；-1 表示从创建事件开始。gap 为 true 时应从业务快照或持久日志恢复。旧 `events` 和 `droppedEventCount` 仍可使用，但跨方法读取不是一个原子视图。

消费者可以注入自己的事件存储。仅接入持久消费者并不能让内存状态和日志原子提交；需要可靠交付、进程崩溃恢复或严格重放的业务，必须选用具备持久状态和 outbox / 确认机制的扩展实现。

## 5. 世界迁移与排行榜通知

`LocalWorldService` 的进入、移动及迁移各阶段统一使用 `LaneKey.entity(entityId)`。目标提交后保留 migrationId 直到源释放；期间移动和第二次迁移被拒绝。释放检查目标 owner、令牌、epoch、状态版本后才完成交接；重复的旧迁移操作不改变新的迁移。异步迁移链在共享状态锁外完成 future，避免后续阶段在锁内重入。

该服务仍为单进程实现，共享状态锁没有移除；没有跨进程租约、持久 fencing token 或故障恢复协调器。业务从 Actor/IO 路径编排迁移时使用异步 API，不在同一 lane 中同步等待自身命令。

`LocalRankingService` 在锁内提交索引、条目、幂等键和赛季状态，在锁外调用事件及指标。回调失败仍向调用者传播，状态不会回滚；这是原有的提交后失败契约。幂等键按既有范围生效，不承担无限期重试去重。慢回调不再阻塞其他线程读写榜单；调用方本身仍同步等待回调，并发通知可能乱序。要求顺序时由业务把相同榜单操作串行投递到 Actor lane，不依赖回调持有服务锁。

## 6. 核查与验证

逐项真实性、风险及实际验证记录见 [九项复核报告](../reports/report-audit-followup-20260928.zh-CN.md)。本地任务档案保留在 tasks 目录，不发布私有协作记录。未新增 release/tag，也未推送远端。
