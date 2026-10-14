# 通用适配与低心智开发目标二轮复查

日期：2026-10-12。版本：`0.1.2-SNAPSHOT`。基线：`b61a8bf3377662211e4cbd14b7bcfb7dcdee9ac6`，与[前轮整体及 62 模块报告](20261012-product-goal-review.zh-CN.md)一致，期间没有业务源码修改。

本轮沿用用户确认的三个子 Agent 分组、主会话复核、只审查并保存报告的方式。三个组交换领域，重点寻找反例、核对承诺边界，并运行有辨识度的本地复现。前轮逐模块矩阵继续适用；本报告记录增量与校正，不声称重新逐行审读所有模块或再次完成全仓测试。

## 1. 结论

**前轮方向判断维持：基础设施的模块化和替换能力较完整，距离“用户主要写业务”的关键差距仍是默认业务组合、状态生命周期、异步结果与上下文、持久化和运营的完整使用路径。** 新增发现进一步说明，应先让已公开的选项、替换契约和失败边界可信，再扩大组件数量。

本轮没有发现必须撤回前轮六项具体问题的反例，但收窄了适用范围，并把 D04、D05 从静态判断提升为当前本地复现。新增两组 P2 问题：缓存过期回退选项没有实际作用；排行榜失败准入与退役后查询存在生命周期边界错误。D04 另补充批量保存的错误分类差异。

本轮没有新增需要立即重构内核的证据。可定制性不足主要指默认流程和高级入口贯通不足，不能概括为不支持自定义业务数据、不能替换服务或没有真实跨进程机制。

## 2. 前轮六项具体问题复核

| 编号 | 处理 | 本轮确认及准确边界 |
| --- | --- | --- |
| D01 / P1 | 保留、限定范围 | Repository 示例组合根显式使用 direct remote IO，真实 provider 正确拒绝；错误属于示例接线。ProductionAssembly 已能延迟创建受管 IO。沿用前轮 Redis 分支连接前复现，未重复连接其他后端 |
| D02 / P1 | 保留、补反证 | Center/Logic Kafka 两端及启动脚本确实没有补上身份签验。框架已有 assertion/verifier，真实 Adapter 测试也有正确接线。错误属于这个公开示例，不能扩展到使用内存 RPC 的本地示例或所有 HTTP 请求 |
| D03 / P1 | 保留、限定触发 | NPC 每轮固定从首部扫描；持续预算不足且前部 NPC 保留时，后部可长期饥饿。扩大预算或移除前部能恢复。沿用前轮两 NPC、十轮、预算一的复现；不称后部在任何条件下都不执行 |
| D04 / P2 | 保留、升级为本轮复现 | 便利 InMemoryCrudRepository 删除后接受旧快照并复活记录，负版本/MAX 首次插入也被归一为 1；标准 local envelope 分别拒绝。新增：便利实现 saveAll 将 VERSION_CONFLICT 变成 WRITE_FAILED。DataRuntime 标准 local source 不受上述差异影响 |
| D05 / P2 | 保留、升级并收窄 | String 便捷入口的 ID→序号目录在收据/赛季清空后仍保留，拒绝请求也增加映射。纯显式序号路径是有效反例，不写入这些目录。不能概括成所有历史都无界 |
| D06 / P2 | 保留、限定异常类别 | 普通 BehaviorAdapter Exception 被隔离后，没有向默认事件/指标端口转交原始 cause/ErrorCode。通知端自身异常、tick 中的 Error 有其他处理路径；不是所有 Throwable 都丢失。业务可自行装饰记录，但需要额外接线 |

### D04：同一 CRUD 契约的本地对照

对照使用便利内存仓库和 `EnvelopeRepositoryFactory.inMemory` 创建的标准 Repository，运行同一组公开 CRUD 操作：

```text
convenience|staleAfterDelete=SUCCESS|stalePresent=true|negativeInsert=SUCCESS|maximumInsert=SUCCESS
convenience|singleStale=VERSION_CONFLICT|batchStale=WRITE_FAILED
standard-local|staleAfterDelete=VERSION_CONFLICT|stalePresent=false|negativeInsert=INVALID_ENTITY|maximumInsert=INVALID_ENTITY
standard-local|singleStale=VERSION_CONFLICT|batchStale=VERSION_CONFLICT
```

源码：[InMemoryCrudRepository.java](../../zero-data/src/main/java/group/zn/zero/data/repository/InMemoryCrudRepository.java) 第 57、65、109、113、117、198 行；标准对照见 [ZeroDataEnvelopeCrudRepository.java](../../zero-data/src/main/java/group/zn/zero/data/envelope/ZeroDataEnvelopeCrudRepository.java) 第 93、154、324、331、334 行。

批量路径使用 `join()` 得到 CompletionException，catch 仅识别直接 ZeroException，最终改写错误码且没有保留原 cause。依赖冲突分类的业务切换实现后会收到不同结果。建议并入 D04 的共同仓库契约修复，不另起一套抽象。

两点限制：本项不要求 saveAll 自动具备批量事务原子性；标准 CAS 对照只证明“记录不存在时拒绝 expectedVersion>0”，不证明删除后主动以 version=0 重建同一 ID 的所有 ABA/代际问题都已解决。MAX 插入被接受是非法版本校验差异，不能据此声称一次普通保存就发生更新版本溢出。

### D05：清空可见收据不等于清空全部历史索引

通过公开 API 提交、确认和退役，反射只读取内部目录大小，不修改状态：

```text
ranking-requests|submitted=1000|receipts=0|legacyIndex=1000
ranking-rejected|rejected=1000|receipts=0|legacyIndex=2000
ranking-seasons|retired=1000|boards=0|legacyIndex=1000
world|explicit=false|capacity=1|retired=1000|records=0|legacyIndex=1000
world|explicit=true|capacity=1|retired=1000|records=0|legacyIndex=0
```

源码：[MigrationRecords.java](../../zero-world/src/main/java/group/zn/zero/world/MigrationRecords.java) 第 18、42、58 行；[LocalRankingService.java](../../zero-ranking/src/main/java/group/zn/zero/ranking/LocalRankingService.java) 第 29、31、277、302、327、331 行。这里确认的是目录项持续保留，没有运行堆字节测量或长稳压测。

建议统一显式序号时，应整体定义模式及迁移边界，不能只替换部分调用。额外 probe 确认：Ranking 显式 submit 序号 101 后，以 String key acknowledge 得到 0，显式 acknowledge 才回收 1；World 显式序号 10 归档后，全新 String 迁移被其独立的低序号判为 MIGRATION_RETIRED。World 的 String acknowledge 本身可以正确确认显式记录，不能一概认定全部重载不能混用。此处作为接入约束与文档待明确项，不重复计为新独立缺陷。

## 3. 新增可复现问题

### D07 / P2：缓存过期回退选项被接受，但没有实际作用

`CachePolicy.allowStaleOnBackendFailure` 的公开注释说明“后端失败时是否允许返回过期本地值”，构造器接受 true。检索已跟踪主 Java 源码，该名称仅出现在 CachePolicy 的字段、注释和构造传递中，没有读取该选项的执行逻辑。

实际读取先经过 `InMemoryCacheService.validEntry`，过期项立即移除；LayeredCacheService 后续只会访问 L2 或 loader。主会话用可切换故障的进程内 CacheStore，先写入并确认命中 OLD，等待记录中的实际 TTL 到期，再令 L2 读失败。分别比较 true/false 及零/正冷却窗口：

```text
allowStale=false|cooldownMs=0|expired=true|get=MISS|getOrLoad=LOADER_FAILED|loaderCalls=1
allowStale=false|cooldownMs=1000|expired=true|get=BACKEND_UNAVAILABLE|getOrLoad=BACKEND_UNAVAILABLE|loaderCalls=0
allowStale=true|cooldownMs=0|expired=true|get=MISS|getOrLoad=LOADER_FAILED|loaderCalls=1
allowStale=true|cooldownMs=1000|expired=true|get=BACKEND_UNAVAILABLE|getOrLoad=BACKEND_UNAVAILABLE|loaderCalls=0
```

源码：[CachePolicy.java](../../zero-cache/src/main/java/group/zn/zero/cache/CachePolicy.java) 第 14、25、46 行；[InMemoryCacheService.java](../../zero-cache/src/main/java/group/zn/zero/cache/InMemoryCacheService.java) 第 345–357 行；[LayeredCacheService.java](../../zero-cache/src/main/java/group/zn/zero/cache/LayeredCacheService.java) 第 201、316、495 行。

影响：用户以为已经选择故障期间旧值可用性，实际仍执行 miss/fail 路径。默认 false 的安全行为不因本项被否定；`cacheLoadedValueOnBackendFailure` 是另一个控制新加载值回填的选项，不等价于过期值回退。

建议：开发阶段可先移除该选项，或明确拒绝 true；确有需求再实现有界、显式启用的 stale 保留与最大陈旧时长。验收应覆盖开启/关闭、后端恢复、显式失效、版本更新、负缓存及容量，防止回退覆盖更新值。仅修改注释无法消除已接受的无效配置。

### D08 / P2：排行榜失败准入和退役后查询存在生命周期边界错误

**场景 A：拒绝未开放赛季的评分，却留下空 CREATED 榜单并阻止合法退役。** 显式序号评分先 `boards.computeIfAbsent`，再检查是否 OPEN。拒绝后，新 Board 仍保留，状态 CREATED、seasonSequence=0。随后创建并取消合法赛季 10，再执行 retireSeasonsThrough(10)，会把这个空榜单也纳入检查并拒绝整个批次。

```text
rejected-score=RANKING_SEASON_STATE_INVALID
boardsAfterRejectedScore=1
unrelated-retirement=RANKING_SEASON_STATE_INVALID
```

该问题独立于 D05：纯显式序号入口也复现。错误请求改变后续合法回收结果，变换不存在的 seasonId 还可积累空目录。应用可以主动取消残留赛季，但这要求用户知道一次拒绝操作的内部副作用。

**场景 B：退役任一赛季后，未创建过的未来赛季被报为 ARCHIVED，却仍可合法 OPEN。** `seasonState` 仅根据全局 retiredSeason>0，就把所有不存在的标识归为 ARCHIVED；该方法注释仍声明“缺失为 CREATED”。

```text
never-created-season-state=ARCHIVED
new-season-can-open=OPEN
```

源码：[LocalRankingService.java](../../zero-ranking/src/main/java/group/zn/zero/ranking/LocalRankingService.java) 第 88–92、191–195、224–233、302–310 行。主会话分别用两个新实例执行以上场景，避免残留状态相互影响。静态检查还发现 transitionSeason 先建目录/登记序号、后验证转换的类似路径；本轮没有为这些变体逐一运行 probe。

建议：在合法提交边界才发布新榜单和序号；查询明确区分未存在与已退役。如果保持有界目录后仅凭字符串无法判断历史身份，应调整查询契约或要求序号/代际，不通过无限 tombstone 目录补救。验收应覆盖失败评分/非法初始转换不改变目录、合法赛季仍可退役、已退役/未来/当前标识的查询结果与可创建性一致。

## 4. 产品差距的校正与补强

| 前轮项 | 本轮反证与准确结论 | 后续应交付的内容 |
| --- | --- | --- |
| G01 默认场景模板 | local 已消费 Player/Scene；其余六类内置模板仍自建 Actor/Store。join 当前位于 CLI 演示，不是正式 IO 线程阻塞的实测证据 | 模板消费正式业务模块；验证能力安装后实际被业务使用，减少从演示到正式项目的重新接线 |
| G02 异步结果及上下文 | 推荐 .si 的 BO 是 void，请求与 server_to_client 是独立消息；旧块式 DSL/公共模型支持同步响应 DTO，低层 dispatcher 按自身契约只返回 boolean。已有入口身份 scope，但普通 ThreadLocal 不自动跨 Actor 线程 | 标准的类型化异步结果、响应发送及最小已验证身份/trace/deadline 承载；保留低层 dispatcher 独立使用 |
| G03 单机到多进程业务 | signed Kafka、Nacos RemoteActor 双 JVM ExternalIT、PG ownership 均已存在；Nacos 已有默认 1 秒、容量 4096、可配置查询缓存 | 同一正式 Player/Room 业务及自定义聚合的 local/remote 配方，覆盖持久化、恢复、旧 owner 拒绝与关闭；Nacos 优化针对冷读/过期的同步 IO |
| G04 玩法定制 | 自定义 DailyRewardInventory/PlayerCharacter 及可替换业务服务已有。固定 PlayerProfile 不等于不能添加业务字段；final 不自动构成设计缺陷 | 可复用聚合生命周期及少数规则策略，由第二种实质玩法证明可复用，不要求所有领域对象泛型化 |
| G05 保存与可靠通知 | DataRuntime.module(bindingExecutor)、scheduleFlush、正常 stop flush、durable 周期重放已存在；默认缺少 Actor 快照与受管周期标脏保存的完整接线 | 明确选择保存语义、完成感知调度和提交后通知；复用已有执行器/重放机制 |
| G06 实时同步 | receiver 基线校验器不承担 payload 应用/ACK，并不违反其低层职责；AOI、FrameSimulation/Broadcaster 也已实现 | 按实时游戏需要提供网络、投影、恢复和节拍的可选配方；验收状态一致和资源有界，不强制所有游戏采用同一种 ACK |
| G07 运营组合 | KCP+monitor 已每秒受管采样；scheduler 有 LoggingScheduledTaskObserver，Netty 有可选指标桥；Prometheus endpoint 文档和真实 HTTP server 已有 | 统一选装系统周期采样、监听、readiness/drain、日志出口与 GM 持久配方，不重复建设桥接器或 HTTP 实现 |
| G08 高级定制 | replace、自定义 ComponentKey、ProductionAssembly.configure/install 已能扩展；Redis namespace/cache-name/failure-cooldown 及 Adapter 超时/在途/探测预算可配置 | 补默认 facade 的少数高级选项：CachePolicy/窄缓存能力、MonitorRuntime 接口型 registry、恢复策略、codec，以及 Kafka verifier 的高层注入 |
| G09 按类型选配 | KCP 真实客户端、双节点内存迁移、独立 Redis Lua 测试已有；Mongo/PG 已服务端分页，Redis 当前页 payload 并非全量加载 | 依据 H5、轻量多进程、平台查询或 KCP 集群需求选配；Redis 每页仍扫描排序全索引，100000 是累计 SSCAN 返回项预算，含可能重复项 |
| G10 接入验收与导航 | center-logic-kafka 已有聚合构建及外部双 JVM 验收命令，CI local-only 不执行真实 Main；构建成功不能证明身份接线正确 | 公共命令清单与无外部服务的接线门禁，覆盖真正被复制的入口；更新滞后的能力矩阵与业务导航 |

### G01/G05 补强：安装数据能力没有自动改变 local 业务消费

[ScaffoldAssemblyRenderer.java](../../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ScaffoldAssemblyRenderer.java) 第 35–50 行会按选择安装 Adapter 与具名 Repository 路由；但 [local Application 模板](../../templates/local-game-scaffold/Application.java.tpl) 第 85–88 行仍调用三参数 LocalPlayerService 构造器，[LocalGameFixture 模板](../../templates/local-game-scaffold/LocalGameFixture.java.tpl) 第 17–23 行也是如此。该构造器在 [LocalPlayerService.java](../../zero-player/src/main/java/group/zn/zero/player/LocalPlayerService.java) 第 105–106 行自行创建便利内存 Repository 和 Cache。

因此选择 data/mongo/redis 等组件证明的是 runtime 能力安装，不证明玩家资料已经消费该后端。Fixture 的 profileLoader 也只检查非空，没有用于玩家加载。这里作为默认业务组合的静态证据，不声称生成器承诺自动替换所有业务存储，更不声称已经执行真实 Mongo/Redis 流程。

建议在入门路径明确区分“安装能力”和“绑定业务”，并让选定玩家持久化配方实际消费 RepositoryCatalog、类型化缓存及受管 IO。验收时断言使用的 provider 和真实数据往返，不能只检查依赖、启动日志或 runtime-plan。

### G02 补强：认证入口到 Actor 再到签名 RPC 的上下文仍需手工恢复

Netty/Kafka 已在验证后的 SecurityContextBridge scope 调用入口 handler；[SecurityContextBridge.java](../../zero-security/src/main/java/group/zn/zero/security/SecurityContextBridge.java) 使用普通 ThreadLocal。ActorMessage/ActorContext 未承载该身份，[RpcRemoteActorReceiver.java](../../zero-rpc/src/main/java/group/zn/zero/rpc/actor/RpcRemoteActorReceiver.java) 第 71–73 行直接 dispatch，ExecutorActorScheduler 的 Actor 线程也不恢复入口身份。

所以跨线程后的第二跳，普通 RpcClientFactory 在没有 current identity 时生成 null metadata；带 assertion 的 RpcRemoteActorGateway 则拒绝缺失身份。业务可 capture/rebind，但缺少统一默认桥。本项是接线负担的静态确认，不是权限绕过，也不是声称所有自定义 CompletionStage 都应隐式携带上下文。

验收应覆盖已认证入口→受管 Actor→第二跳 signed RPC，以及用户隔离、延迟完成、取消、过期身份；只传播必要的已验证身份，不传播密钥。

### G05 补强：scheduleFlush 没有把异步完成结果交给调度观察

[DefaultPersistenceManager.java](../../zero-data/src/main/java/group/zn/zero/data/persistence/DefaultPersistenceManager.java) 第 344–347 行将 `() -> flushNow()` 传给 Runnable 调度接口，返回的 CompletionStage/PersistenceFlushResult 被丢弃。第 312–315 行最多排队 128 次，队列满是失败 future；该定时入口不能把它交给调度器观察。

正常对象写失败已有 failures()/统计和隔离记录，不能写成保存故障完全静默或数据被丢弃。准确缺口是周期任务只看到触发返回，没有看到真实完成、失败条目或准入拒绝。可复用已有 PeriodicAsyncTask 的单次在途与完成感知能力，再明确如何分类 failureCount，避免应用各自补通用定时保存逻辑。本轮此项为静态核对，未运行队列满故障注入。

### G08 补强：全量 production builder 缺少 Kafka verifier 的启动前注入

[ZeroProductionRuntimeBuilder.java](../../zero-server-starter-production/src/main/java/group/zn/zero/starter/production/ZeroProductionRuntimeBuilder.java) 第 34、80–83 行只使用默认 KafkaRuntime.module 或其 properties 重载，接收 verifier 为 failClosed；securityChain 仅传给 NetworkRuntime。公共 builder 没有对应 verifier、替换 Kafka module 或通用 configure 入口。

模块化入口 [KafkaRuntime.java](../../zero-runtime-kafka/src/main/java/group/zn/zero/runtime/kafka/KafkaRuntime.java) 第 29–37 行已经支持 properties+verifier，可经 ProductionAssembly 正确注入。启动后也可取得具体 lifecycle adapter 的 delegate 再设置，但 delegate 启动前为空，预注册 handler 会在发布 delegate 前回放，应用需要了解具体类型与操作顺序。

主会话仅执行 diagnose/build/close，没有 start、没有创建真实 broker 连接：

```text
kafkaNoVerifierDiagnosis=ENABLED|missing=[]|warnings=[]
kafkaNoVerifierBuild=BUILT|transport=KafkaRpcLifecycleAdapter
```

这证明便利入口与诊断没有揭示接收身份意图，不能证明服务已运行、broker 健康或消息已拒绝。建议高层入口开放一致的 verifier 注入，并区分 sender-only、配置接收身份和显式拒绝接收；不能一律要求只发送的应用提供 verifier，也不能弱化 failClosed 默认。

## 5. 建议实施顺序

| 顺序 | 工作 | 完成依据 |
| --- | --- | --- |
| 1 | 修复 D01–D03 的公开使用断点及运行错误 | 正确执行域、Kafka 双端身份接线、NPC 多轮公平性；公开入口有回归 |
| 2 | 统一 D04/D05/D07/D08 及 D06 的替换、失败和诊断契约 | 共同 Repository 契约、全历史索引边界、策略真实生效或明确拒绝、排行榜失败不污染生命周期、保留 NPC 原因 |
| 3 | 围绕一个带自定义持久数据的玩家业务贯通 G01/G02/G05 | 同一协议→异步 BO→Actor→保存→响应，业务不维护通用 dispatcher 桥、上下文恢复或 flush 定时器 |
| 4 | 在该业务上交付 G03/G07，并补具体 G08 入口 | 真正双 JVM、真实数据源、接管/重启/关闭；观测和安全在启动前装配 |
| 5 | 按实质游戏需求推进 G04/G06/G09 | 第二种玩法规则、真实客户端同步、H5/KCP/查询等独立选配，不扩大最小 core |

G10 的公共命令与接线验收应贯穿上述各步。前轮分阶段完成定义继续适用；本轮新增问题不需要再复制一份宏大路线图。

## 6. 本轮验证与限制

环境：Windows，JDK 21.0.4（`D:/env/jdk21`）。Maven 3.9.8 可用但本轮没有运行 Maven；源码和基线未变，复用前轮已构建的 target/classes 运行 Java source launcher。没有改全局 JAVA_HOME/PATH。

| 本轮主会话执行 | 结果 | 日志（仓库根目录起算） |
| --- | --- | --- |
| RepositoryParityProbe | exit 0；删除后旧写、非法版本、单条/批量错误分类对照符合上述结果 | target/product-goal-recheck-20261012/repository-parity-main.log |
| RetentionProbe | exit 0；1000 次提交/归档及拒绝增长；显式 World 序号对照映射为 0 | target/product-goal-recheck-20261012/retention-main.log |
| RankingBoundaryProbe | exit 0；拒绝评分残留、退役阻塞、未来赛季误报及混用约束 | target/product-goal-recheck-20261012/ranking-boundary-main.log |
| TransportRecheckProbe | exit 0；真实旧 DSL 生成、编译、执行 BO/dispatcher；Kafka 仅诊断/构建/关闭 | target/product-goal-recheck-20261012/transport-main.log |
| CacheStalePolicyProbe | exit 0；等待真实 TTL 后，对照 true/false、零/正冷却窗口 | target/product-goal-recheck-20261012/cache-stale-policy-main.log |

以上 exit 0 表示复现程序成功观测并检查预期边界，不表示相关产品缺陷已修复。临时 Java 源码及生成目录均在同一个 target 路径；共享构建只由主会话负责，子 Agent 没有并发运行 Maven。

主要复现命令：

```powershell
$probeRoot = 'target/product-goal-recheck-20261012'
$java21 = 'D:/env/jdk21/bin/java.exe'
& $java21 --class-path 'zero-core/target/classes;zero-data/target/classes;zero-protocol/target/classes;examples/repository-composition/target/classes' "$probeRoot/RepositoryParityProbe.java"
& $java21 --class-path 'zero-core/target/classes;zero-actor/target/classes;zero-ranking/target/classes;zero-world/target/classes' "$probeRoot/RetentionProbe.java"
& $java21 --class-path 'zero-core/target/classes;zero-actor/target/classes;zero-ranking/target/classes;zero-world/target/classes' "$probeRoot/RankingBoundaryProbe.java"
$transportCp = (Get-Content "$probeRoot/transport-classpath.txt" -Raw).Trim()
& $java21 --class-path $transportCp "$probeRoot/TransportRecheckProbe.java" "$probeRoot/transport-generated-main"
& $java21 --class-path 'zero-core/target/classes;zero-cache/target/classes' "$probeRoot/CacheStalePolicyProbe.java"
```

源码、命令和日志依赖现有本地构建产物，target 清理后需先构建再重建 probe；报告保留关键输入、输出与源码定位。任务及六份分组证据归档到 `tasks/archive/20261012-product-goal-recheck/`。

没有重跑前轮 Stage 0 quick、1354 个默认测试或示例套件；它们属于前轮执行结果，不能算成本轮新增通过数。未执行真实 Kafka/数据库/Nacos/Redis、Actor 第二跳身份 probe、完整 quality、真实客户端/双 JVM 玩法、容量/长稳/跨平台、安全或灾备验收；文中现有 ExternalIT 仅作为能力反证，不提升为本轮实测成功。

本次只修改报告、索引与任务材料，没有业务代码、协议、公共 API 或存储行为变更，不产生本轮迁移要求。用户原有 `docs/audits/2026-10-06-full-code-audit.zh-CN.md` 修改保持原状。未提交或发布；后续行为修复仍需遵守仓库的 0.x 迁移说明与验证规则。
