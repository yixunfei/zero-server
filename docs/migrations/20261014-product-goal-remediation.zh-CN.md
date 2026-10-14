# 2026-10-14 产品目标报告修复与玩家链路迁移

适用版本：`0.1.2-SNAPSHOT` 开发工作区。未发布。任务依据为 [2026-10-12 二轮报告](../reports/20261012-product-goal-recheck.zh-CN.md)。本页覆盖 D01–D08 和本轮确认的玩家异步响应、上下文与持久化配方；不代表原报告所有产品目标完成。

## Repository（D01、D04）

外部 `repository-composition` 使用 `ProductionAssembly.builder(profile, config)` 默认受管 IO，移除 `ZeroRuntimeExecutors.direct()`。普通命令行负责启动与关闭，CRUD 在受管远程 IO 域执行。无需引入新的执行器或后端抽象。

`InMemoryCrudRepository` 创建不存在记录必须使用 version=0；负版本和 Long.MAX_VALUE 返回 INVALID_ENTITY；删除后旧快照返回 VERSION_CONFLICT，不复活记录。批量失败保留原始错误码及原因，不再因 join 包装变成 WRITE_FAILED。调用方遇到版本冲突应重读，不能盲目重放写入。

批次仍可能部分提交；不提供批量事务。显式 version=0 重建同一 ID 的代际/ABA 问题不在本次修复承诺内。便利内存实现仍存储调用方实体引用，业务应使用不可变实体。

## Kafka 示例（D02）

Center/Logic 启动前注入相同的 `ZERO_CENTER_LOGIC_SECRET`，为至少 32 字节随机材料的 Base64。验收脚本每次随机生成并仅通过子进程环境传入，结束恢复原环境；错误信息不回显材料。手工部署由部署配置提供，不将测试 secret 提交仓库。

Logic 每次 RPC 在短期服务身份与 trace scope 内签名；Center 在 handler 注册前装配 verifier，只接受当前 run/topic prefix 的服务主体及 center:invoke 权限。默认 failClosed 不变。此配方不替代 TLS、Kafka ACL 或密钥轮换。

`scripts/VerifyCenterLogicKafka.ps1 -BootstrapServers <address>` 支持复用已有 broker；脚本先聚合 clean install 再解析子模块依赖，等待 Center 就绪后启动 Logic，核验两个 JVM 退出码、3 次请求及 active=0。使用外部 broker 时不修改该 broker 生命周期；测试 topic 保留，不清空共享数据。

## NPC（D03、D06）

调度顺序由固定起点改为轮转，单次选择为常数开销；候选选择计入 tick 时间预算，零预算不推进决策。保持原 zone lane、锁外回调和单 NPC 普通异常隔离。

`NpcEvent` 增加第六个参数 `ZeroException failure`：BEHAVIOR_FAILED 必须非空，其他类型必须为空。消费者通过 failure.errorCode()、failure.getCause() 和事件 traceId 获取诊断；不要把内部异常原样序列化给客户端，也不要修改共享异常对象。普通非 ZeroException 使用 SYSTEM_ERROR 分类，原始异常仍作为 cause。

## 持久化异步调度（G05 的局部修复）

`PersistenceScheduler.scheduleAtFixedRate` 的任务由 Runnable 改为 `Supplier<? extends CompletionStage<?>>`。自定义 scheduler 必须观察实际完成，前次任务未完成时跳过重叠触发，异步失败不能被忽略。`PersistenceScheduleHandle` 新增 failureCount()/lastFailure()；分别记录任务失败次数与最近失败，不是失败对象数量。

`DefaultPersistenceManager.scheduleFlush` 将实际 flush stage 交给调度器。result.failureCount()>0 也视为一次任务失败；条目级原因继续通过原有 persistence failures 查询，dirty 保留与重试策略不改变。

应用组合可注入 `new ManagedPersistenceScheduler(runtime.require(RuntimeBasics.EXECUTORS))`。它位于 zero-runtime-data，复用已有 PeriodicAsyncTask；需要非内联 remote IO，取消在关闭预算内等待在途任务。句柄由 PersistenceManager 正常停止时取消，框架执行器最后关闭。不要在 Actor lane 或任务自身回调内阻塞等待取消。

## World / Ranking 显式序号（D05、D08）

移除所有自动 String→sequence 分配入口、String 确认/退役重载及旧 Migration 构造。调用方必须分配正序号，重试复用原序号和业务键：

- World：迁移序号为每个 World service 的范围；`migrateAsync(sequence, migrationId, entityId, targetShard)`；通过 `acknowledgeMigrationsThrough(sequence)` 释放已归档终态。
- Ranking：`transitionSeason(..., seasonSequence, key)` 同一赛季全部转换复用同一序号；赛季序号和 `retireSeasonsThrough` 水位为整个 service 的范围。
- `submitScore(..., requestSequence, key)` 的评分序号/`acknowledgeRequestsThrough` 水位只属于单个榜单赛季，与赛季序号无关。
- `seasonState(rankingId, seasonId, seasonSequence)`：活跃代际必须匹配；缺失且不高于退役水位返回 ARCHIVED，未来缺失返回 CREATED。非法评分/初始转换不创建空 Board。

推进水位前调用方必须确认边界内无在途或未来到达的新请求，且结果已归档。服务只保存活跃收据及标量水位，没有无界 ID 墓碑；归档后不能识别“旧字符串身份换新序号”的错误复用，业务必须维护代际分配约束。未归档活跃业务本身仍占内存，不代表任意新赛季/玩家总数已被容量限制。

## CachePolicy（D07）

两个构造入口均拒绝 `allowStaleOnBackendFailure=true`，抛 IllegalArgumentException；迁移配置为 false。已有新加载值回填开关和后端冷却不等于过期值回退。本轮没有实现 stale 缓存。

## 类型化异步协议与可信上下文

重新生成 Java 协议。生成 BO 新增嵌套 `Async<R>`，方法返回 CompletionStage；有显式 response 声明时返回该 DTO，否则组合根绑定 R。同步 boolean dispatcher 仍可独立使用。`register...Async`/`dispatchAsync` 返回实际业务阶段；生成 `endpoint...` 工厂组合 request codec、异步 BO 和响应 codec。业务无需 Consumer<CompletionStage<?>> 结果旁路；不得在 IO/Actor 内 join。

`AsyncProtocolEndpoint` 在副作用前检查完整 payload，成功编码响应号/DTO，失败使用标准错误帧。它不执行认证。入口先经 SecurityChain 验证，再捕获 RequestExecution；ContextualActorScheduler 必须用于同一业务的注册与投递。身份来自已验证 SecurityContext，RPC 使用原 RpcCallContext 的 reply topic 和剩余预算，密钥不进入请求范围。只有框架拥有的入口与交付回调显式恢复 scope；业务自己创建的任意异步回调需通过 scope.call 显式恢复，不承诺 ThreadLocal 自动传播。

取消、断连、deadline 和身份过期阻止新执行及成功交付，不撤销已提交写入。ActorRequestExecutor 一个 scheduler 装配一个，物理阶段完成前保持 lane，关闭前先排空业务。

local 模板的 login/scene response 取代请求回显，新增 server_to_client 协议（当前默认号 90201/90202）；旧客户端需重生成 DTO/codec。Fixture 从 players 角色加载正式 PlayerProfileRepository（schema/codec 1），默认 local；选择唯一外部数据源时默认绑定它，可通过 `zero.player.repositorySource` 覆盖。该模板仍是固定 UID 的 loopback 原型，不把它的 token 字符串当作安全认证；已认证接线参考 business-slice。

## Actor 脏状态保存

`PersistenceManager.markDirty` 增加保存确认 Consumer 重载。成功写入后通过同一 binding executor 回到所属 lane 确认新版本，期间新标脏代际不会被删除。确认失败保留待确认项，下次先重试确认，不重写已经提交的快照；确认回调必须幂等且不能再次发奖。旧无回调入口适合外部自行维护快照版本的场景。

BoundPersistentEntity 只接受不可变状态，update 保留 ID/版本并在标脏准入成功后替换状态；保存确认只推进版本，不覆盖并发新增业务字段。update 成功表示内存接受，不能当作奖励/扣款的耐久确认。

使用 ActorPersistenceBindingExecutor 绑定快照与确认，ManagedPersistenceScheduler 调度完成感知 flush。关闭顺序：停止新业务与入口 → 停止/排空持久化管理器 → 关闭绑定器及业务注册 → Actor 和受管 IO。单个句柄取消失败不会跳过其余句柄或最终保存；取消耗时计入后续 drain 剩余预算，但自定义 cancel 的阻塞上限仍由调度器契约负责，受管句柄使用执行器 closeTimeout。

## 每日奖励 schema 3 与通知

DailyRewardInventory 从 schema/codec 2 升为 3，增加 pendingNotification 与 notificationTrace。开发示例数据按新版重建；没有静默兼容读取或自动改写旧库存。需要保留数据时应离线转换并核对库存/日期/版本，不能删除真实玩家数据。DailyReward.si 新增 claimResult 响应；客户端重新生成。

库存、领取日期/首次键和待通知状态一次 CAS；提交成功后失效缓存、发布事件，再 CAS 确认。固定事件 ID 为 daily-reward-uid-day，消费者必须幂等去重。发送成功但确认失败允许重复发送，不承诺 exactly-once。同一玩家只保存一个待通知槽，未确认旧日事件则拒绝下一日领取，防止覆盖通知或无限积压。

DailyRewardNotificationReplay 用受管周期任务分页扫描（每页 64）并在玩家 lane 重放；失败保留并可观察。分页为弱一致遍历，下一轮从头继续，非分布式 outbox claim 协议。重复请求不再增库存；未知数据库写结果不自动重试发奖，后续查询领取日期确认。

## 本轮实际证据与边界

Ranking 基准已迁移显式序号和分批确认；共享写入只确认连续已完成水位，避免跳过其他线程的在途请求。测量包含序号分配与确认协调开销，历史数字不再是同口径基线。本轮仅执行短时生命周期冒烟，没有性能提升结论。

完整命令、日志和结果见 [验收报告](../reports/20261014-product-goal-remediation.zh-CN.md)。环境为 Windows / Java 21 / Maven 3.9.8，本机外部依赖位于 M:/docker_space。

真实 PostgreSQL 恢复是同 JVM 关闭后重新建立 runtime/连接池，从持久层恢复，并非杀进程故障注入。真实 Kafka 为双 JVM。未验证跨进程玩家 owner 迁移、宕机恢复、容量长稳、复制集切换和生产认证供应商；这些仍属于原报告的后续产品目标。
