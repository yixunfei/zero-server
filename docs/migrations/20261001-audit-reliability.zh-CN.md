# 2026-10-01 可靠性修复与耐久提交迁移

本次是已确认的 0.x 开发期 API 调整，不提供同步兼容包装，不改变 Maven 版本，不发布制品。继续保持 `productionReady=false`。

## 1. World、Room 与 Player 执行域

- `LocalWorldService` 删除阻塞的 `enterWorld`、`moveEntity`、`requestMigration`、`prepareMigration`、`commitMigration`、`releaseSource`、`migrate` 入口。业务使用对应 `*Async` 的 `CompletionStage` 并组合后续操作，不能在 Actor 或 Netty IO 线程 `.join()`。
- World 的状态及事件历史在短锁内提交，事件消费者和 metrics 在锁外调用。消费者失败时返回失败信号，但状态已经提交；不要把通知失败当成状态回滚而无条件重发。跨实体通知可能并行，消费者需线程安全；同 lane 消费者不得同步等待该 lane 的后续消息。
- 全局状态锁仍保留；此次解决持锁外部调用，未宣称已证明锁分片收益。
- Room 通过一次 volatile 写发布不可变 `RoomView`。`snapshot()` 和 `stats()` 各自读取完整已提交值；两次独立调用之间可能发生新提交，不构成两次查询的事务快照。
- `LocalPlayerService` 的自定义 Repository/Cache 构造必须提供受管远程 IO Executor。UID resolver、首次 IO 及后续异步 IO 链都使用该执行域。只有框架内存后端允许省略 Executor；显式 Executor 的调用者仍负责避免内联执行。
- Player、World、Room 仍是单进程服务。本次未给它们隐式接入 ownership lease/fencing，也不承诺多进程写入安全。

```java
world.enterWorldAsync(entityId, worldId)
    .thenCompose(entity -> world.moveEntityAsync(entityId, entity.ownerShardId(), x, y));
```

## 2. 快照保存与原子条件写

所有 Envelope Store 的 `save()` 契约统一为：不存在时接受任意合法正版本；允许同版快照重放和跳版本；拒绝版本回退。同版保存允许替换内容，**不是并发修改的 CAS**。并发更新仍使用 Repository 或 `saveIfVersion()`，其中 `expectedVersion=0` 表示仅当不存在才插入。`ZeroDataEnvelope` 不允许版本 0。

Redis 原子脚本按完整 long 文本比较，避免超过 2^53 后的 Lua 浮点精度丢失。MongoDB/PostgreSQL 真实驱动原本就符合以上 save 契约；本次补充契约验证并修复它们的内存模拟存储。

Redis `findAll()` 按 Cluster 桶 SSCAN 并分批 MGET，批次 256；索引目录及对象 ID 扫描分别有 100000 条硬预算，超限返回错误，不静默截断。`count()` 按桶 pipeline SCARD。跨桶读是弱一致查询，不是全局事务快照。

## 3. 普通标脏：有界重试，仍不耐久

`DefaultPersistenceManager.markDirty()` 只接受内存标记，JVM 崩溃前尚未保存的数据仍可能丢失。成功落库会移除对应脏代际，并非“脏表只增不减”。

默认每键最多尝试 5 次，失败退避从 100ms 指数增长、上限 30s。版本冲突/非法快照等不可自动修复问题立即隔离；重标脏不清空失败预算。`failures()` 返回诊断；修正原因后可 `retryFailed(target, id)` 显式恢复。失败和隔离项保留，占用原有容量；满额明确拒绝而非丢弃数据。停机尝试排空，隔离项会使 stop 明确失败，不无限自旋。单实例等待 flush 请求最多 128。

## 4. 新增显式耐久提交

入口在 `zero-data/persistence/durable`，与普通标脏分开：

```text
绑定域捕获完整不可变信封
  -> 受管 IO 域追加意图与 CRC32C
  -> FileChannel.force(true)
  -> submit 完成，返回 PENDING 收据
  -> 周期/显式 flushNow 执行后端 CAS
  -> 刷盘 APPLIED 或失败/隔离状态
```

`submit(operationId, target, expectedVersion, binding, snapshotSupplier)` 的成功表示**意图已写入保留的本地磁盘**，不表示后端落库成功。待写信封版本必须为 `expectedVersion + 1`，操作 ID、payload、schema、codec、更新时间等完整元数据在重试时须保持稳定。同 ID 同意图幂等返回原收据；同 ID 不同意图拒绝。同一目标/实体最多存在一个未解决意图。

运行时接入示意（`targets`、`bindingExecutor` 由应用提供）：

```java
var runtime = RuntimeComposition.builder(RuntimeProfile.local())
    .install(RuntimeBasics.module(config, () -> ZeroRuntimeExecutors.localPrototype("game", 2)))
    .install(DurablePersistenceRuntime.module(journalDirectory, targets, bindingExecutor))
    .build();
runtime.start();
var durable = runtime.require(DurablePersistenceRuntime.SERVICE);
durable.submit(operationId, "players", expectedVersion, binding, () -> immutableEnvelope)
    .thenAccept(receipt -> acknowledgeDurableAcceptance(receipt.operationId()));
```

上述业务回调和变量为应用提供的示意；`bindingExecutor` 必须真正把可变实体捕获派发到所属 Actor/lane，不能在 IO 域读取 live 对象。直接构造服务必须传入非内联受管 IO Executor，服务本身不创建线程。

运行时模块 build 时恢复日志，start 后每秒最多重放 128 条，停止时先取消周期任务、等待已接受捕获和刷盘，再关闭日志与执行器。后端 Store 由应用拥有，必须晚于该模块关闭。取消返回 future 不撤销已经接受的意图。

恢复时只做条件写；后端响应丢失时，通过读取并比较完整信封核验未知结果。不同内容或版本冲突进入 `QUARANTINED`，绝不盲目覆盖。`records()` 查看收据；修正外部原因后 `retry(operationId)` 清除该条重试预算，仍遵守原 CAS。多次失败默认沿用 5 次/指数退避策略，状态跨重启保留。

### 日志与容量边界

- 使用独立版本化 `intents.zwal` 与 OS 独占 `journal.lock`；同目录不能被第二个实例打开。
- 不完整尾帧可截断；长度反码不匹配或完整帧 CRC 损坏拒绝启动，不能把损坏数据当作可丢弃尾部。
- 默认运行时预算为 256 MiB、100000 个操作；单帧最大 16 MiB，准入最多 1024 个在途请求。低层日志构造及 `DurablePersistenceRuntime.module(..., maxBytes, maxOperations)` 重载允许显式配置预算。
- 已完成收据也保留以支持操作 ID 去重。`compact()` 只合并同一操作的历次状态，不淘汰收据；容量耗尽明确拒绝，不能靠重启清空。持续服务需要监控容量并在停机维护时扩大预算或规划应用级收据归档，不能删除未解决意图来腾空间。
- 字节耗尽也可能阻止结果记录刷盘；该意图保持待核验状态，扩大预算后重放会核验后端已提交快照，不应改用无条件 save。
- 磁盘写入异常表示未知或未确认，实例停止继续追加；重新打开日志核验，不能直接宣布该请求丢失或成功。
- 保证仅覆盖成功确认的本地提交和磁盘仍保留的 JVM 崩溃恢复。未提交内存、磁盘丢失、跨机器复制、OS/硬件断电行为不在本次验证保证内。
- 这是本地耐久意图协议，不是跨数据库事务、消息恰好一次或多进程 ownership 协议。

Redis `LocalDiskDataJournal` 现在仅在 Redis 写入/删除成功后追加镜像。它不是提交前 WAL；Redis 拒绝 CAS 时不追加。Redis 成功但镜像失败可能返回未知结果，应重新核验后端，不能把镜像当成两套介质原子提交。需要提交前耐久保证时使用上述独立服务。

## 5. 缓存与受管调度

- 写回按键合并，容量 1024。满额返回 `BACKPRESSURE_REJECTED` 并增加 `writeBackRejectedCount`，不静默挤掉最旧待写值。
- 写回携带原读取代际，后续 put/invalidate 会使旧写回失效，避免重试复活旧值。队列仍是非持久缓存补偿，不是业务数据耐久日志。
- `ProductionRedisCacheProvider` 生命周期每秒最多重试 128 条，停止后等待在途任务。手动构造 Cache 仍由组合根调度 `retryWriteBacks`，核心 Cache 不自建线程。
- 生产 Redis cache 启动需要非内联 remote IO 执行域。默认 direct 装配不满足该要求，必须在 builder 上显式 `.executors(ZeroRuntimeExecutors.localPrototype("game", 2))` 或注入符合契约的执行器；否则启动明确失败。仅 build 成功不代表该执行域已满足启动条件。
- 未版本化 put 先读取后端缓存版本再生成更高版本，冲突最多尝试 3 次；不能以未版本化写覆盖 entity-versioned 值。不能依赖进程本地从 0 开始的计数器解决重启后的版本比较。
- Kafka Adapter/PendingRequests 构造新增必须传入的 `ScheduledExecutorService`。通过 `executors.scheduledExecutor("kafka-rpc")` 获取受管计时器；Adapter 关闭仅取消任务，执行器由运行时关闭。自定义装配同步提供 `RuntimeBasics.EXECUTORS`。

## 6. 核验结论与验证入口

H1/H2/H3/H5、M3、L5 是有效问题；H4 的故障控制/耐久性成立，但“只增不减”描述不准确。M4 当前真实驱动并无所述语义差异；M5 的 count 原本按桶查询，且没有全局快照承诺；M6 是能力边界和 IO 接入问题，不能据此把 LocalPlayerService 当作分布式实现。L4 的生产 PostgreSQL 已有 Hikari 池，无池 settings 构造保留给测试与低频工具。L7 是既有工作区状态，不是修复目标。

回归入口包括 World/Room 并发测试、`PersistenceRetryBudgetTest`、`CacheWriteBackReliabilityTest`、`DurablePersistenceServiceTest`、`DurableSubmissionLifecycleTest`、`DurablePersistenceRuntimeTest`、`PeriodicAsyncTaskTest` 和三类 `*EnvelopeContractExternalIT`。子 JVM 测试在确认刷盘后 `Runtime.halt(0)`，父进程重新打开日志重放。最终实际命令与结果记录在本任务 `VERIFY.md`，不以编译成功代替外部系统或崩溃恢复证据。
