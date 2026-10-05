# 2026-10-03 并发、异步与性能迁移说明

适用于 `0.1.2-SNAPSHOT` 开发阶段。本轮保持既有执行域、模块依赖方向、公开方法签名和协议/存储格式，修复实现竞态、资源回收与热点成本。验证记录见[专项审计报告](../reports/concurrency-async-performance-20261003.zh-CN.md)。

## 取消、超时和容量

- 分层缓存的 `put`、失效、回填等已接受写入保持同键顺序。取消返回的观察 Future 不撤销已经启动的底层 IO，也不能提前释放顺序屏障。读超时仍放弃旧读取代际；后续失效等待此前物理写结算，防止旧写迟到后复活数据。应用应给真实 IO 配置驱动期限，不能把 `CompletableFuture.cancel` 当作远端操作已停止。
- 缓存内部已接受、尚未结算写入包含运行和排队项：每键最多 1024，全实例最多 4096，超限以现有 `BACKPRESSURE_REJECTED` 拒绝。拒绝不改变键代际，取消观察不提前归还配额；同步完成链采用非递归推进。不增加后台线程。
- `InMemoryCacheService` 现在落实现有 `CachePolicy.maxConcurrentLoads`；同键共享一次加载，失效后仍未结算的旧加载继续占用配额。依赖此前无限并发加载的代码需处理背压。
- Player 的同 UID cache-miss 仓库读取和回填现在共享一次内部加载，防止迟到旧加载覆盖较新缓存；最多 4096 个未完成 UID，观察者取消不清除共享流程，close 终结等待者。应用直接写缓存仍需自行协调版本。
- `retryWriteBacks()` 调用方取消不再让内部重试状态永久保持 busy；内部批次负责释放状态。
- Kafka pending 的成功、失败、取消和关闭会立即移除时间轮登记，不再等待原截止时间；超时与 correlationId 令牌契约不变。
- `InMemoryGmIdempotencyStore` 最多保留 4096 项，新 claim 时按到期索引回收。容量满时新键返回 `FAILED`，已有键的去重/冲突行为保持，GM 入口将其映射为拒绝。高容量或耐久需求应注入应用的 `GmIdempotencyStore`。

## 数据与 ownership

- Redis cache 中双方 `entityVersion=0` 的条件写现在按 envelope 既有 `cacheVersion` 比较，允许进程重启后观察版本并覆盖更新；相同或更低版本拒绝。实体正版本 fencing 保持，64 位版本比较不经过 Lua 浮点数；无需改键、清空缓存或重编码数据。
- PostgreSQL `release` 保留 ownership 行的 epoch 历史，只使当前租约过期；重新获取时 epoch 递增，旧句柄不能再次生效。不要人工删除 ownership 历史行来模拟 release。迁移前已经删除的历史无法自动恢复；上线切换应更换进程 incarnation，并停止使用旧进程的租约句柄。
- PostgreSQL fenced commit 在取得 ownership 行锁后重新检查 requestId 收据；并发的相同请求返回原结果，不再误报 CAS 冲突。
- `InMemoryFencedStateStore` 的现有构造器现在只接受 `InMemoryOwnershipStore`，否则立即抛 `IllegalArgumentException`。本地 lease 验证和状态写在同一 ownership 临界区完成。此前把远程 ownership 与本地状态混搭的代码应使用对应事务状态实现，例如 `PostgresqlFencedStateStore`；签名未变，但这是显式的装配行为收紧。
- 持久化定时 flush 的登记与 stop 交错时会取消迟到句柄，不遗留关闭后的定时任务。

## 异步失败与生命周期

- Actor executor、Player 回投、RPC decoder、KCP handler/store、HTTP handler、持久化快照等扩展抛出 `Error` 时，框架完成对应失败结果并清理已取得的资源，保留故障原因。此行为保证等待者获得终结信号，不表示 JVM 严重故障可安全忽略或自动恢复。
- 受管 Scheduler 的业务执行器拒绝或抛 `Error` 会回收执行租约，并按既有策略终结一次性任务或重排周期任务；观察执行器失败不会打断业务状态机。timer 首次登记或重排失败会释放任务槽位，避免留下无法再次触发的活动任务。
- KCP 的业务执行器提交、响应发送和失败关闭在 dispatch 锁外执行；帧仍串行，执行器仍必须非内联。Redis KCP 先归还物理 IO 配额再完成观察 Future，容量为 1 的连续异步调用不会误拒绝；同一受管线程延后执行不属于内联。
- UDP 异步 handler、响应校验和发送失败会通知具体连接的异常观察器；HTTP handler 失败返回 500，写失败先通知异常观察器再结束连接。应用的异常观察器应处理这些此前遗漏的失败。
- 生产网络生命周期 observer 现在按连接有序排队且最多保留 1024 个任务；超限只拒绝当前 observer，保留已排队 drain。底层执行器首次提交失败会清空队列、复位 drain 并按既有 observer 失败路径关闭连接。慢 observer 仍需由应用控制执行时间和整体容量。
- `ServerDrainOrchestrator` 并发调用共享一次 drain，关闭回调最多执行一次、在内部锁外调用。强制关闭后的重复调用返回同一 `false` 结果，失败结果保持异常；跟随调用超时/中断不发起第二次关闭。关闭回调重入 drain 返回 false，禁止自等待。
- 配置 watcher 可以从自己的回调关闭；关闭不等待当前监听线程自身。执行器提交抛 `RuntimeException` 或 `Error` 时清理未执行任务并返回 `EXECUTOR_REJECTED`，关闭不再误等到超时。配置表目录使用不可变快照，读取不竞争生命周期锁。此修复不使任意用户回调之间的同步循环依赖变为安全。

## 游戏循环与性能

- Room 命令复用已提交的不可变视图作为回滚检查点，只有提交前失败才恢复可变成员；提交后的通知失败仍不回滚。避免每条命令复制完整事件历史，查询与事件顺序契约保持。
- NPC 任一 tick 预算为零时直接跳过，不执行第一次业务决策；耗尽预算后停止遍历剩余候选。单次外部 Adapter 调用仍不可被预算检查强制抢占。

## 升级验证

运行默认测试及 `-Pquality,benchmarks,integration-tests verify`；使用独立 Redis 键和 PostgreSQL 表前缀运行报告列出的 external 用例。调整调用方对缓存取消、过载拒绝、drain 返回值和内存 ownership 装配的假设。无需新增版本兼容层；回退代码会重新引入本报告列出的竞态，不应在保留在途工作时混合运行两种生命周期逻辑。
