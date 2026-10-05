# 并发、异步与性能专项审计

日期：2026-10-03（Asia/Shanghai）。基线提交 `a4d254ae23242acfa552fa48a4a04a3261198f87`，分支 `zero/concurrency-async-performance-audit`。审计保留开始时已有的脚手架、脚本与文档修改。验证使用本机 JDK 21.0.4、Maven Wrapper 3.9.8、Windows 11；系统默认 Java 17 未用于项目验证。

## 范围与方法

按模块与资源所有权分三组阅读控制流和现有回归，主 Agent 复核跨模块边界及其余模块，串行运行 Maven/外部服务验证。重点是共享状态、异步桥接、物理 IO 与观察 Future 生命周期、锁顺序、队列容量、关闭竞争和热点分配。使用可控 Future、队列、latch、故障注入和真实数据库事务复现，不将默认测试通过解释为没有竞态。

| 模块组 | 检查路径 |
| --- | --- |
| core、actor、event、runtime、runtime-bootstrap/actor/event | 生命周期、lane 串行与预算、回调注册、拒绝、异常、资源登记/清理、受管执行域 |
| player、scene、world、room、game、npc、aoi、ranking、frame-sync、state-sync、logic | owner/lane 状态归属、版本/代际、幂等、迁移、提交与通知边界、tick 预算、历史分配 |
| net、net-kcp、net-kcp-redis、rpc/common/kafka/discovery、discovery/nacos 及相应 runtime | 连接生命周期、共享 IO 所有权、缓冲/预算、Kafka pending/批次/rebalance、KCP 调度与 store、异步异常及订阅快照 |
| data 及 Mongo/PostgreSQL/Redis Adapter、cache、ownership/PostgreSQL 及相应 runtime | 条件写、loader 合并、取消和回填、写回恢复、普通/耐久持久化、租约 fencing、幂等事务、配额及连接归还 |
| starter、production starter、runtime-production、hot-update | drain、受管调度器、启动预算、配置表发布、文件监听循环、自等待和关闭登记 |
| gm/rest、log、monitor、安全、protocol、codegen/templates、剩余 runtime 装配 | GM 去重保留、同步日志/SPI 责任、样本容量、HTTP 生命周期、ThreadLocal 恢复、缓冲借用、生成器输出锁、模板调用边界 |
| parent、bom、benchmarks、scripts/docs | 测试与质量配置、静态依赖守卫、可重现性能证据及迁移说明 |

没有逐行证明全部程序正确；仅将确认触发条件和修复证据完整的问题记为已修复。

## 修复清单

| 领域 | 已确认问题及修复 | 主要回归 |
| --- | --- | --- |
| Actor | executor 抛 Error 时 lane/准入泄漏；统一终结队列并回收 | ActorExecutorFailureTest |
| Scheduler | 业务/观察执行器与 timer 抛 Error 时租约、任务槽位泄漏或周期任务失去后续触发；复用失败清理和重排路径 | SchedulerExecutorErrorTest |
| Player | IO/Actor 回投 Error 后独立结果永不完成；同 UID 仓库 miss 并发回填可倒退缓存；有界合并读取和回填，关闭/取消观察隔离 | PlayerDispatchFailureTest、PlayerRepositorySingleFlightTest、MissingPlayerPolicyTest |
| Room | 每条命令复制全部事件历史与成员；复用提交视图，失败时恢复，提交后通知失败不回滚 | RoomCheckpointBoundaryTest、RoomCommitBoundaryTest、分配 probe |
| NPC | 零时间仍执行首次决策、耗尽操作预算后继续扫描；决策前检查并终止剩余遍历 | NpcTickBudgetBoundaryTest |
| Kafka | 完成/取消请求留下超时桶记录直到原截止；按令牌立即回收并处理登记竞争 | KafkaTimeoutRetentionTest、KafkaReportAuditTest |
| KCP | dispatch 锁内外部执行/发送造成锁反转；handler/发送/store Error 遗留队列、授权或内存预算；外部调用移出锁，失败完整收敛 | KcpDispatchConcurrencyTest、KcpOwnershipCompletionTest |
| Redis KCP | 先完成结果再归还许可，容量 1 连续异步调用误拒绝；同线程延后排队误当内联；改准入结算和实际内联检测 | RedisKcpAsyncAdmissionTest |
| RPC | decoder Error 落入被丢弃的 dependent stage；独立调用结果永久等待；补异常终结 | RpcAsyncCompletionTest |
| TCP/UDP/HTTP/observer | TCP 异步异常跳过负责监听/关闭的 handler；UDP 响应/写失败丢失；HTTP handler Error 与写失败不终结请求；observer 队列无上限且执行器 Error 可能卡住 drain；错误返回所属连接，HTTP 写失败先通知再关闭，observer 每连接最多排队 1024 项并在提交失败后复位 | TcpAsyncFailureTest、UdpAsyncFailureTest、HttpAsyncFailureTest、ProductionNetworkLifecycleFocusedTest |
| 缓存加载 | InMemory 未执行 maxConcurrentLoads；独立实际在途许可，失效/取消不提前归还，通知前释放完成许可 | CacheOperationLifecycleTest |
| 缓存写序 | observer 取消提前解除同键写序，旧写越过失效；保留实际结算屏障，对外复制观察结果 | CacheOperationLifecycleTest、LayeredCacheConcurrencyTest |
| 缓存容量/栈 | 同键/全局写无上限，同步完成递归耗尽栈；加入 1024/4096 准入与当前线程非递归完成，防双结算 | CacheWriteAdmissionTest |
| 写回/持久化 | 取消写回观察导致 busy 不清；定时登记晚于 stop 泄漏；快照 Error 遗留 flush；清理依附内部阶段、登记代际与统一失败处理 | CacheWriteBackReliabilityTest、DefaultPersistenceManagerTest |
| Redis cache | entityVersion=0 的第二次写总冲突；复用既有 envelope cacheVersion，精确保留 64 位比较 | RedisCacheConcurrencyExternalIT |
| PostgreSQL ownership | 相同 requestId 等行锁后未重查；release 删除 epoch 历史使旧 lease 复活；锁后查收据并保留过期行 | PostgresqlOwnershipExternalIT |
| 本地 ownership | lease 校验与状态写非原子；本地组合共享 ownership guard，无法原子组合的混搭构造立即拒绝 | InMemoryFencingConcurrencyTest |
| Drain | 并发超时/重入重复 forceClose，可能提前 STOPPED；共享关闭结果、回调锁外、失败/重复调用一致、释放唤醒 | ServerDrainConcurrencyTest、ServerDrainOrchestratorTest |
| 配置 watcher | 回调关闭自身自等待、异常回调再抛使监听结果悬挂、执行器 Error 留下未执行任务、表目录读与 stop 锁环；检测循环线程、清理提交失败、完成失败、快照读 | LocalConfigFileWatcherTest、CsvConfigHotReloadFocusedTest |
| GM | 声称有界的内存幂等表实际无限保留唯一请求；4096 容量与到期索引，满额明确拒绝 | GmIdempotencyCapacityTest、InMemoryGmIdempotencyStoreTest |

## 性能证据

Room probe 使用直接 Actor、满 1024 条事件历史，对已加入成员执行幂等 join，预热 20,000 次，测量 5 轮，每轮 20,000 次。JDK ThreadMXBean 读取当前线程分配量，固定 `-Xms256m -Xmx256m`。基线与修复后使用同一探针、同一 JDK，测量期间无 Maven 构建。

| 样本 | 修复前 B/op | 修复后 B/op | 修复前 ns/op | 修复后 ns/op |
| --- | ---: | ---: | ---: | ---: |
| 1 | 4835.70 | 496.01 | 3802.21 | 3947.87 |
| 2 | 4824.00 | 496.00 | 4039.77 | 2085.60 |
| 3 | 4818.62 | 496.00 | 4018.56 | 848.34 |
| 4 | 4800.00 | 496.00 | 3428.72 | 773.54 |
| 5 | 4771.70 | 496.00 | 2385.50 | 1401.52 |

分配中位数从 4818.62 降为 496 B/op，减少 89.7%。时延样本受 JIT 和共享主机影响明显，仅列原始值，不据此承诺吞吐或尾延迟改善。探针保存在 `zero-benchmarks/.../performance/RoomCommandAllocationProbe.java`，可通过 benchmark jar 的同名主类复跑。

Kafka 使用确定性资源证据：pending 容量为 1，长截止的 300 次成功/失败/取消循环后桶保留记录为 0。缓存写入测试排满每键 1024 和全局 4096，验证超额拒绝、取消不提前释放、全部物理完成后配额恢复。以上证明指定资源边界，不是生产压测。

## 验证记录

- 修改前全量 `mvnw.cmd -B -ntp test`：1012 个测试，失败/错误/跳过为 0。
- 定向回归、外部服务和质量门禁日志保存在 `target/concurrency-audit/`。最终 `-Pquality,benchmarks,integration-tests verify` 通过：1063 个 Surefire 测试、1 个 Failsafe 本地集成测试，失败/错误/跳过均为 0；新增 watcher 提交 Error 回归随后在 zero-hot-update 依赖闭包中通过。
- 真实 Redis 7.4.2 新增 2 例、PostgreSQL 16.4 ownership 8 例通过，失败/错误/跳过为 0；后者包括独立 JVM 争抢。复用 `zero-flexibility-20260930`，使用 UUID 键与独立表前缀，没有运行共享数据库清空操作。
- 架构守卫：61 模块、21 规则、0 违规/警告；framework boundary 通过。benchmark 新增 Room probe 的显式依赖已同步守卫和模块图。
- API 兼容门禁：5 个受保护模块通过，独立 Java 21 consumer 编译通过；一次历史 baseline 漂移已按既有迁移说明同步为当前 `RuntimeAssemblyPlan` 构造器签名。

复现外部验证时，设置 `ZERO_REDIS_URI` 和 `ZERO_EXTERNAL_POSTGRES_URL/USER/PASSWORD_FILE`，执行：

```text
mvnw.cmd -B -ntp -pl zero-data-redis,zero-ownership-postgresql -am -Pexternal-tests -Dtest=RedisCacheHealthCheckTest,OwnershipContractTest,InMemoryFencingConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RedisCacheConcurrencyExternalIT,PostgresqlOwnershipExternalIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

修复前 drain 两条测试稳定失败，watcher 自关闭测试稳定超时。整合时修正了旧缓存回归对物理 IO 可取消的错误假设；Player 创建竞争测试改为两个独立服务以保留并发 CAS 场景；新外部测试的一处字段名编译错误已修正。HTTP 回归暴露了先关闭再通知会移除观察器的问题，已调整顺序，并为新增异步测试补齐等待期限或完成状态断言。失败记录保留在任务日志，不通过跳过断言完成验证。

API 符号门禁初次发现一处历史基线漂移：`RuntimeAssemblyPlan` 在任务基线提交中已使用带 `RuntimeConfigDiagnostics` 的构造器，但清单仍要求六参数构造器。核对 [2026-09-30 迁移说明](../migrations/20260930-flexibility-foundation.zh-CN.md) 后，仅更新该清单行以保护已迁移的七参数签名；没有改运行时 API，也没有批量生成基线或添加旧版兼容层。

## 运行边界

单机测试不证明 Kafka/Nacos 集群重平衡、跨机分区、磁盘介质故障、生产长稳或容量 SLA。真实 MongoDB/普通 PostgreSQL Repository 未在本轮重跑外部测试。自定义同步 Repository/codec/日志 SPI 仍由应用保证执行域与线程安全；逐条查询和全量物化接口的规模须由应用限制。底层 IO 永久不返回时屏障仍占用配额，必须配置驱动期限。

NPC/AOI/state-sync 保持单 owner 契约；非内联 executor 要求保持；本地 ownership/GM 存储不等于生产持久化服务。配置 watcher 修复不消除应用回调自行构造的生命周期循环等待。`productionReady=false` 保持。

升级影响、取消语义、过载拒绝和 ownership 装配约束见[迁移说明](../migrations/20261003-concurrency-async-performance.zh-CN.md)。
