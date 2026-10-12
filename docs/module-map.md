# zeroServer 模块图

## 2026-10-12 模块接入应用

新增 [examples/module-recipes](../examples/module-recipes/README.md)，是独立消费者聚合工程，不进入框架发布 Reactor。Java 包为 `group.zn.zero.examples.recipes.<场景>`；各子工程只向下依赖框架，核心与 Adapter 不反向依赖示例。

| 入口 / 常见修改位置 | 职责与主要依赖 | 边界 |
| --- | --- | --- |
| `runtime-basics/RuntimeBasicsExample` | 配置、诊断及受管任务；runtime-bootstrap | 不安装网络；执行器统一关闭 |
| `event-actor/EventActorExample` | 内部事件到玩家 lane；runtime-event、runtime-actor、game | 固定玩家状态由一个 lane 独占，回调不阻塞 |
| `discovery/DiscoveryExample` | 健康/集群筛选与业务标签选择；runtime-discovery | 内存目录不代表真实服务就绪 |
| `npc/NpcExample` | 正式 NPC 生命周期与次数预算；npc、runtime-actor | 决策失败隔离，通知失败不回滚 |
| `ownership/OwnershipExample` | 本地租约、CAS、幂等和主动交接；ownership | 不代表分布式原子提交或宕机恢复 |
| `gm/GmExample`、`AnnouncementCommand` | REST 到授权/审批/幂等/审计；gm-rest | 本地身份 fixture，无网络监听或公告外发 |
| `production/ProductionExample` | 实际 Starter 配置诊断及基础生命周期；server-starter-production | 隔离进程配置，无外部 Adapter 健康验收 |

聚合 POM 只管理测试和构建，禁止将 GM/production 的具体驱动依赖扩散到纯基础示例；业务禁止自行创建线程池。现有 62 个模块的其他入口见[模块接入卡片](../examples/module-access/README.md)，验证记录见[迁移说明](migrations/20261012-module-recipes.zh-CN.md)。

2026-10-12 示例审核：`examples/business-slice/DailyRewardService` 负责按服务端日期限领，`DailyRewardInventory` 将库存和领取标记聚合为一次版本条件写；已移除独立领取记录实体。`examples/multi-server-player` 的 `PlayerLifecycleService` 负责普通调用线程上的本地业务编排，`CenterRouter` 统一注册与粘性路由，`role/RoleResults` 保留业务 ErrorCode，`rpc/RpcInputs` 校验契约字段。RPC 接口当前是未装配 codec 的草图；外部组件探针独立于玩家流程。常见修改入口见 [模块接入卡片](../examples/module-access/README.md) 与 [示例迁移说明](migrations/20261012-examples-review.zh-CN.md)。框架模块、依赖方向及 core 禁止依赖中间件的约束保持不变。

2026-10-11 上手修复：`zero-runtime/RuntimeComponentBuilder` 负责带依赖的延迟单值工厂与资源登记；`ComponentContribution.healthSnapshot` 和 `DefaultGameRuntime` 汇入内存 READINESS，不依赖 production 或驱动。`zero-runtime-production/RuntimeAdapterSettings` 解析五类独立运行预算，`ProductionAssembly` 默认延迟创建受管 IO；各 Adapter provider 接入共享健康及原生超时。`zero-codegen/ScaffoldCatalogFile` 的 bindings 从一份声明派生组件图与工厂，`ScaffoldWrapper` 从生成器资源导出 Wrapper；Runner 按生成 POM 安装依赖闭包。常改位置为以上类和入门指南，模块依赖方向保持不变。

2026-10-11 Redis 实测补充：`zero-data/envelope/ScopedEnvelopeStore` 保留已分类的非数据类框架错误码，避免上层 Repository 丢失拒绝和未知写结果语义；仅依赖 `zero-core/ErrorCode`，不引用 production 类型。`zero-server-starter-production` 的 `RedisRuntimeRecoveryExternalIT` 与测试专用 `RedisRecoveryFaultProxy` 验证真实 Redis 数据/缓存共用状态的断连、超时、恢复和写结果；代理只影响本次测试连接，常改位置为上述测试与 `ObservedEnvelopeStore`。

2026-10-10 Runtime Adapter 恢复：`zero-runtime-production/RuntimeAdapterIo` 管理单 Adapter 准入、故障分类、健康快照与受管周期探测；`RuntimeAdapterMonitoredLifecycle` 管理先关闭准入再停止真实资源。Redis data/cache 经 `zero-runtime-redis/ProductionRedisResource` 共享监测；MongoDB、PostgreSQL、Kafka RPC、Nacos 的 provider 分别在各自 `zero-runtime-*` 集成模块接线。`zero-data` 的 `ObservedEnvelopeStore` 与 `zero-data-redis` 的 `ObservedRedisCacheStore` 位于驱动 IO 路径；驱动模块不反向依赖 runtime，使用注入的 observer。`zero-runtime/RuntimeModule.value` 是 Java 短入口，`zero-codegen` catalog 示例生成同一安装表达式。常改位置为上述类及各模块测试；依赖仍为 starter → 集成层 → 中立 runtime/驱动，`zero-core` 禁止具体中间件依赖。同步 Nacos 接口可阻塞调用线程，Kafka RPC 未知结果仍需业务幂等；见[迁移说明](migrations/20261010-runtime-adapter-recovery.zh-CN.md)。

2026-10-09 审计 F1/F2：`zero-player/PlayerSessionBindings` 管理账号/UID 双向绑定、有界清理意图、重试合并及身份代际；`LocalPlayerService` 负责锁外投递与完成通知，资料删除仍在 player lane。`zero-codegen/TargetNames` 检查完整生成类型与包前缀，`generator/java/JavaCompilationUnitNames` 校验实际模板单元的声明/导入；均在输出计划应用前执行。常改位置为上述入口及对应 main/test；依赖方向不变，无新增线程池或外部存储。容量与调用方重试边界见 [迁移说明](migrations/20261009-audit-f1-f2.zh-CN.md)。

2026-10-08 复查后续：`FrameMatchRuntime.closeAsync` 负责可观察、可重试的 lane 清理，异步关闭迁移见[迁移说明](migrations/20261008-audit-followup.zh-CN.md)。依赖方向未改变；`scripts/ZeroDependencyGuard.java` 负责父链/profile/BOM 静态依赖模型、生产环与硬约束，`VerifyDependencyGuard.java` 负责隔离正负夹具；完整节点与声明边见[审核清单](reference/module-dependencies.tsv)，修改流程及保守边界见[门禁契约](reference/dependency-guard-contract.zh-CN.md)。

2026-10-08 审计 B5：`zero-runtime-bootstrap/ExecutorShutdown` 负责自有 timer→worker 的累计关闭预算；`zero-event/EventInterceptor.afterPublish` 负责事件级逆序完成清理，通用重试/幂等仍是缺口。`zero-world/MigrationRecords` 管理有界收据，`WorldDispatchRegistry` 管理共享 scheduler 无状态注册；`zero-room/RoomCommandAdmission` 管理创建通知前及内联转交窗口的有界 FIFO 屏障。`zero-npc/NpcDispatchRegistry` 把修改发送到 zone lane，`NpcNotifications` 聚合锁外通知故障；`zero-logic/LogicSessionErrorCode` 与属性容器负责身份、容量、关闭及类型冲突。常改位置为各模块 main/test 与行为契约；不新增依赖、线程池、存储或授权供应商，core 禁止具体中间件依赖不变。0.x 异步 NPC API、World 枚举删除及容量风险见 [B5 迁移](migrations/20261008-audit-b5.zh-CN.md)。

2026-10-08 审计 B0–B2：`zero-gm/GmOperationEndpoint` 为外部授权执行边界；`GmSecondReviewerVerifier` 绑定可信第二人审批，`GmOperationFingerprint` 管理部署级 HMAC，`GmOperationResponse` 分别表达提交状态、审计失败与幂等失败。`zero-security/SecurityContextExpiry` 管理共享单调到期预算，`SecurityContext.withTransport` 派生时保留预算；`zero-net-kcp/KcpSession` 使用该入口。常改位置为 GM、安全模块及 GM REST 响应映射；依赖方向不变，不引入具体认证或存储供应商。公共 API 与密钥作用域风险见[迁移说明](migrations/20261008-audit-b0-b2.zh-CN.md)。

2026-10-07 审计边界：`zero-gm/GmOperationEndpoint` 通过包内 `GmCommandExecutor.resolve` 固定命令解析，`GmOperationAuthorizer` 绑定实际目标并合并审批要求；常改位置为 GM/REST 契约测试。`zero-protocol/ZeroReader.readPresenceBits(knownFieldCount)` 由 `zero-codegen` 四语言 codec/runtime 模板传入 schema 计数，升级需重新生成；禁止使用不可信线长度作分配上限。`zero-monitor/PrometheusAlertRuleExporter` 分别处理 PromQL/YAML 转义。模块和依赖方向未变，风险与升级见[迁移说明](migrations/20261007-audit-n-series.zh-CN.md)。

2026-10-07 审计收尾：`zero-room/RoomDispatchRegistry` 只管理 scheduler 注册，消息绑定所属 Room 服务；`zero-protocol/ProtocolErrorFrames` 定义共享错误帧，net 与 local 模板共同使用；`zero-monitor/MetricTextEscaper` 统一文本转义。Mongo Adapter 顺序调用逐项 CAS，player 只接受权威 `PlayerUidResolver`，不内置账号 ID 服务。Frame 参与者预算/leave、World 初始分片与迁移枚举、GM 幂等状态、HTTP 校验截止及 Actor 关闭责任均在原模块内收口；`NpcErrorCode` 提供 NPC 领域失败。依赖方向不变，core 不新增中间件依赖；常改位置、性能取舍与 API 风险见[迁移说明](migrations/20261007-full-code-audit-remediation.zh-CN.md)。

2026-10-06 用户流程修复：zero-gm-rest 的 GmRestRequest 负责严格 JSON 校验，GmRestResponses 负责状态和白名单结果投影；Gson 仅增加在 REST Adapter，core 无新依赖。PrometheusHttpEndpoint 通过 BooleanSupplier 注入 /ready，不依赖 runtime。local 模板由 LocalGameFlow/LocalGameBO 共享异步业务，LocalGameLifecycle 负责本地就绪和关闭控制；仅本地 Server 使用受 loopback 限制的 tcpUnmanaged。scripts/ZeroProcess.java 统一两个 shell 入口的 JVM 身份与启停，ZeroUnifiedEntryVerifier 提供真实生成项目验证。ScaffoldComponents 分开判断档位与外部服务；风险与迁移见[说明](migrations/20261006-user-flow-reliability.zh-CN.md)。

2026-10-05 性能入口：`zero-data/envelope` 强制注入 IO executor，Store SPI 增加批量和分页，`persistence` 提供跨键在途预算；生产三驱动覆盖分块协议。`zero-data-postgresql/PostgresqlPool` 统一 HikariCP，依赖由 runtime 下移到数据 Adapter；外部 DataSource 保持调用方所有权。`zero-runtime-bootstrap/ZeroRuntimeExecutors.remoteIo` 提供受管虚拟线程 IO，production 便利工厂默认采用该入口。`zero-state-sync/SyncPayloadHash、PreparedSyncPayload` 支持一次准备共享摘要；`zero-net-kcp/KcpProtectionStrategies` 复用 Context 原语。`zero-runtime-kafka` 负责成功计数和实例独占 reply 路由。core 不引入具体驱动，迁移与风险见[说明](migrations/20261005-performance-issues.zh-CN.md)。

2026-10-03 并发审计：`CacheKeyOperations` 统一同键物理写顺序、写准入与非递归完成；`InMemoryFencedStateStore` 只组合本地 ownership 并原子验证/写入；PostgreSQL Adapter 保留 epoch 历史并在锁后重查幂等收据。`KafkaRpcTimeoutWheel` 按令牌回收记录，`KcpDispatch` 将外部调用移出调度锁。运行时依赖方向不变。`zero-benchmarks` 新增仅用于证据的 `zero-room` 依赖及 `RoomCommandAllocationProbe`，运行时不得反向依赖。常改位置、行为与风险见[迁移说明](migrations/20261003-concurrency-async-performance.zh-CN.md)。

2026-09-30 易用性基础：`zero-core/config/ConfigLookup` 与 `SourcedZeroConfig` 统一候选读取及来源；runtime 的 `RuntimeAssemblyPlan.configuration` 保存同次解析的安全快照，`RuntimeAssemblyDiagnosis` 统一生成入口。`zero-codegen/scaffold/ScaffoldCatalogFile` 显式读取版本化 JSON，并贯通 CLI、生成、升级。依赖方向不变（codegen → runtime → core），核心不引入 JSON/驱动；常改位置为上述类型、ScaffoldAssemblyRenderer 和 templates。来源优先级与公共 API 风险见[迁移说明](migrations/20260930-flexibility-foundation.zh-CN.md)。

2026-09-30 通用 ownership 基础设施：`zero-ownership` 定义资源 generation、进程 incarnation、lease/epoch、fenced state CAS、requestId 幂等、owner 路由、带 checkpoint 元数据的迁移恢复及有界容量契约，并提供仅用于单进程原型/测试的内存实现。`zero-ownership-postgresql` 独立实现 ownership/state/migration/route 存储，复用应用提供的 DataSource 和 payload codec；JDBC 驱动仅由该 Adapter 引入。依赖方向为可选业务/组合根 → ownership API，PostgreSQL Adapter → ownership API；核心、Room、World 不反向绑定具体存储或彼此依赖。常见修改位置是 `zero-ownership`、`zero-ownership-postgresql` 与迁移说明。单实例外部测试已通过；schema migration 生命周期、数据库复制集切换、消息 transport、跨机房及生产容量仍需应用选择并验证，生产就绪为 false。

2026-09-28 高级 KCP：`zero-net-kcp` 在 ZKCP v1 数据面提供可插拔 HMAC/ChaCha20-Poly1305/AES-GCM、XOR/Reed-Solomon FEC、挑战响应路径验证和有界租约；`KcpSessionStore`/`KcpSessionCoordinator` 提供冻结、迁移、代际 fencing，`zero-net-kcp-redis` 为可选 Redis Lua 适配。`zero-runtime-kcp` 可注入会话 SPI。密钥仍只经已认证控制面传递，跨节点目标重建必须由应用安全传递新票据。
`KcpProfile/KcpTuning/KcpLimits/KcpTimeouts` 提供场景和分组设置；`KcpConnectInfo` 是可信控制面描述；`KcpClient/KcpRecovery` 完成受管 Java 接入和显式恢复/回退。
`KcpScheduler` 管理有界截止索引，`KcpRetainedFrame` 在整帧实际释放时回收预算；双端复用 `KcpDispatch` 和段校验。常改位置为两个模块、examples/kcp-tcp-login 和[场景指南](guides/kcp-scenarios.zh-CN.md)。
风险包括时限调度、共享缓冲生命周期、业务重放和可选资源依赖；Redis 适配器故障时必须 fail-closed。

`zero-runtime-kcp -> zero-runtime-monitor` 为 optional 编译依赖，不向消费者传递。`KcpRuntimeMonitoring` 隔离可选监控类的加载；没有该依赖时最小 `KcpRuntime.module(...)` 正常启动，没有显式安装监控 provider 时不创建采样任务。应用可显式依赖并安装监控模块，或直接使用 `KcpServer.watch(...)` 注入自己的快照消费者。`KcpOptionalDependenciesTest` 用隔离类路径验证缺少全部日志/监控模块的实际启动与关闭。

2026-09-28 KCP 入口：新增 `zero-net-kcp -> zero-net -> protocol/event/security/core`，第三方 `kcp-base` 仅在 Adapter 中引入。
`KcpServer` 负责服务生命周期与组合根 API，`KcpSessions` 负责 TCP 授权/票据注册，`KcpSession` 负责单 EventLoop 算法，
`KcpConnection` 提供线程安全业务句柄，`KcpDispatch` 管理有界有序回调，`KcpBudget` 管理发送预留，
`KcpDatagramCodec` 管理认证线格式和重放窗口，`KcpOptions`/`KcpTicket` 是配置/凭证。
共享 IO 通过 `NettyIoResources.bindDatagram` 接入；禁止 `zero-net`、`zero-core` 反向依赖 KCP Adapter 或业务 BO。
常见修改位置是该模块主代码、测试及 [KCP 契约](reference/kcp-transport-contract.zh-CN.md)；主要风险是密钥下发、
地址绑定、异步队列预算及回退重复请求，不应自动重放业务或使用第三方自建线程池。

2026-09-27 codegen 入口：`CodegenProjectConfig` 读取 CLI/GUI 共用工程设置；`CodegenExecution` 协调只读预览、检查和执行；
`SiProtocolProjectParser` 登记全工程符号，`SiProtocolDslParser` 解析显式 `@id(...)`。
`DefaultCodeGenerator.plan` 只渲染；`GeneratedOutputPlan` 负责文件索引；`ManagedOutput` 负责摘要归属与过期计划；
`OutputManifest`、`OutputTransaction`、`OutputFiles` 分别管理清单、加锁恢复、底层文件操作。
依赖方向仍为 codegen → runtime/protocol；Gson 从测试依赖提升为 codegen 的 JSON 配置依赖，不引入核心模块。
禁止生成器反向调用业务 BO 或修改 DSL；未完成事务必须先恢复，手改生成物和 BOImp 不得自动清理。
行为、目标支持与验证边界见[codegen 迁移说明](migrations/20260927-codegen-generalization.md)。


性能增量入口（2026-09-23）：`zero-event/bus/InMemoryEventBus` 负责单版本注册快照和异步续调；
`zero-aoi/ObserverState` 保存观察者代次和原始序号，候选工作区由 `InMemoryAoiIndex` 管理；
`zero-protocol/buffer/ReadOnlyZeroBuffer` 适配只读输入，`ProtocolCodec.decodeView` 提供自定义 codec 默认回退，
Java dispatcher 模板生成 `dispatchFrame`。`LocalRankingService` 维护最多 100 引用的单项 Top 缓存。
模块依赖、业务执行域不变；主要风险为阶段交接、借用内容稳定性与回调重入，详见[增量迁移](migrations/20260923-performance-incremental.md)。

本文给出 zeroServer 当前公开模块的职责、依赖方向、核心入口、常见修改位置、禁止依赖与风险边界。模块、包结构、核心入口或依赖方向发生变化时，应同步更新本文和对应测试。

2026-09-30 灵活性增量：`zero-runtime/config` 新增 `RuntimeConfigDiagnostics` 安全有效配置视图；`zero-codegen/scaffold` 以 `ScaffoldComponentDefinition` 统一 Provider、坐标、安装表达式和配置样例，并支持应用显式目录。依赖方向不变；配置诊断不创建资源，脚手架目录不扫描 classpath。

2026-09-30 ownership / 迁移恢复增量：新增中立 `zero-ownership`，提供资源 generation、进程 incarnation、lease epoch fencing、可选 owner 路由、状态版本 CAS、requestId 幂等、带 schema/codec 元数据的可恢复迁移状态机、显式 `FailoverCoordinator` 和无后台线程的有界容量预算/状态装饰器。该模块仅依赖 `zero-core` 与 JDK，不绑定 World、Room、PostgreSQL、Kafka、Nacos 或监控；业务绑定和生产持久化必须由应用显式装配的 Adapter 完成。当前内存实现只用于本地原型和契约测试，productionReady=false。

2026-09-22 常见修改入口：`zero-rpc-kafka/KafkaRpcConsumerBatch` 在 consumer 线程管理批次异步确认与重平衡；
`zero-runtime-kafka/KafkaRuntime.module(properties, verifier)` 注入应用安全验证器；
`NettyHttpServer` 使用请求级验证身份；`PrometheusHttpEndpoint` 接收外部异步执行器和可选 token。
依赖方向不变，安全验证材料由应用持有。RPC 重投与鉴权配置风险见[迁移说明](migrations/20260922-security-storage-concurrency.md)。

性能路径入口：`zero-actor/scheduler/ExecutorActorScheduler` 维护分段 lane 队列，
`OrderedActorHandlers` 负责有序注册快照；`zero-aoi/SpatialGrid` 负责空间桶，
`InMemoryAoiIndex` 负责实体和观察者增量；`zero-protocol/codec/EncodingWriters` 负责同步编码临时缓冲复用，
`buffer/NativeMemoryZeroBuffer` 负责显式 native 生命周期。以上辅助类均为包内实现，无新增模块依赖。
常见风险是 lane 回收竞态、网格边界漏查和缓冲借用视图逃逸，详见[迁移说明](migrations/20260922-performance-feedback.md)。

## 1. 总体分层与依赖方向

zeroServer 的基本形态是“游戏专用运行时内核 + Starter / Adapter 生态”。依赖只能从上层指向下层抽象，核心模块不感知具体中间件。

```mermaid
flowchart TB
    APP["业务应用 / 运营 API"] --> STARTER["zero-server-starter"]
    APP --> PROD["zero-server-starter-production"]
    APP --> INTEGRATIONS["zero-runtime-bootstrap / 所选 zero-runtime-* 集成模块"]
    INTEGRATIONS --> RUNTIME["zero-runtime"]
    PROD --> STARTER
    PROD --> INTEGRATIONS
    INTEGRATIONS --> ADAPTERS["各自的端口与 Adapter"]
    STARTER --> GAME["zero-game / zero-player / zero-scene"]
    STARTER --> INFRA["zero-net / zero-rpc / zero-data / zero-cache / zero-log / zero-monitor / zero-gm / zero-hot-update"]
    ADAPTERS --> INFRA
    GAME --> FOUNDATION["zero-event / zero-actor / zero-protocol"]
    INFRA --> FOUNDATION
    FOUNDATION --> CORE["zero-core"]
    RUNTIME --> CORE
    BENCH["zero-benchmarks（显式 profile）"] --> PROD
```

硬边界：

- `zero-core` 不依赖 Netty、Kafka、MongoDB、Redis、PostgreSQL、Nacos、日志实现或监控实现。
- `zero-runtime` 的非测试直接依赖必须且只能是 `zero-core`；它不扫描 classpath，也不反向依赖 Starter、Adapter 或业务模块。
- `zero-event`、`zero-protocol`、`zero-actor` 只建立在核心抽象之上，不绑定具体基础设施。
- `zero-rpc`、`zero-data` 提供中立抽象；具体实现位于 `zero-rpc-kafka`、`zero-data-*`。
- 默认 `zero-server-starter` 保持无外部中间件可运行；真实 Adapter 由业务项目或 `zero-server-starter-production` 显式接入。
- 业务代码不得自行创建线程池；执行域由 bootstrap 集成统一装配，受管调度器由对应组合根管理。
- 玩家、场景等核心状态遵循 Actor / lane 线程绑定；跨 Actor 修改通过消息完成。
- `zero-benchmarks` 是显式启用的证据叶子，任何运行时模块都不得反向依赖它。

## 2. 构建与版本模块

| 模块 | 职责 | 核心入口 / 修改位置 | 禁止依赖与主要风险 |
| --- | --- | --- | --- |
| `zero-parent` | Java 21、插件版本、测试与质量 profile 的统一父 POM | `zero-parent/pom.xml` | 修改编译器、Surefire、Failsafe、Checkstyle、PMD、SpotBugs、JaCoCo 会影响全部模块 |
| `zero-bom` | 对外提供 zeroServer 模块版本对齐 | `zero-bom/pom.xml` | 不承载运行时代码；新增模块后要同步依赖管理 |

## 3. 核心内核与基础抽象

| 模块 | 职责 | 核心入口 | 常见修改位置 | 禁止依赖与主要风险 |
| --- | --- | --- | --- | --- |
| `zero-security` | 传输中立的认证、重放、TLS 材料与安全上下文契约 | `AuthenticationProvider`、`SecurityContext`、`ReplayProtection`、`TlsMaterialProvider`、`SecurityChain` | `zero-security/src/main/java/group/zn/zero/security` | 仅依赖 `zero-core`；不内置账号/JWT/证书供应商；安全 provider 必须显式注入，敏感字段禁止进入上下文 |
| `zero-runtime` | 显式组件选择、typed config、有效配置诊断、依赖图、事务式生命周期、健康、安全诊断与共享能力模型 | `RuntimeAssembler`、`RuntimeComposition`、`RuntimeConfigDiagnostics`、`GameRuntime`、`ComponentCatalog`、`RuntimeProfile`、`RuntimeCapabilityModel` | `zero-runtime/src/main/java/group/zn/zero/runtime` | 只依赖 `zero-core`；禁止 classpath 自动装配和具体端口/Adapter 依赖；配置诊断不创建 provider 或资源 |
| `zero-event` | 事件总线、优先级、拦截和死信；通用重试/持久幂等未实现 | `EventBus`、`InMemoryEventBus`、`EventHandler`、`EventInterceptor` | `zero-event/src/main/java/group/zn/zero/event` | 不绑定网络或消息队列；派发顺序、重试和异常语义属于高风险行为 |
| `zero-protocol` | 协议模型、注册表、frame 与 codec SPI | `ProtocolCodec`、`ProtocolFrameCodec`、`ProtocolRegistry`、`ProtocolDefinition` | `zero-protocol/src/main/java/group/zn/zero/protocol` | 协议 ID、wire format、兼容策略与编解码变化会影响客户端和跨服通信 |
| `zero-codegen` | 协议 DSL 解析与 Java / C# / TypeScript / GDScript 代码生成，以及按统一元数据目录生成项目脚手架 | `ProtocolCodegenCli`、`DefaultCodeGenerator`、`ProjectScaffoldCli`、`ScaffoldComponents`、`ScaffoldComponentDefinition`、`ScaffoldUpgradeService` | `zero-codegen/src/main/java/group/zn/zero/codegen`、`scaffold/ScaffoldComponents.java`、`scaffold/ScaffoldComponentDefinition.java`、`src/main/resources/codegen`、`zero-codegen/src/test` | 依赖 runtime/protocol，不依赖 net/logic；组件 ID、Provider、坐标、安装表达式和配置样例必须来自同一定义；未知或冲突目录项在写盘前失败；协议生成与 scaffold ownership 独立 |
| `zero-actor` | Actor 地址、lane key、调度、路由与跨 Actor 消息抽象 | `ActorScheduler`、`ExecutorActorScheduler`、`LocalActorScheduler`、`ActorRouteResolver` | `zero-actor/src/main/java/group/zn/zero/actor` | 禁止直接绑定 RPC、Netty 或中间件；调度、队列、线程归属和背压是关键性能路径 |
| `zero-ownership` | 跨进程 ownership、epoch fencing、可选 owner 路由、状态 CAS/幂等、迁移恢复、故障转移编排和有界容量治理中立契约 | `OwnershipStore`、`OwnershipRouter`、`FencedStateStore`、`MigrationStore`、`FailoverCoordinator`、`CapacityBudget`、`CapacityBoundStateStore` | `zero-ownership/src/main/java/group/zn/zero/ownership` | 仅依赖 `zero-core` 与 JDK；禁止绑定 World/Room 或具体数据库、消息队列、监控；内存实现不代表生产分布式一致性 |
| `zero-world` | Actor-owned 本地 world/shard、实体归属、迁移状态机、不可变查询快照与低基数观测最小切片 | `LocalWorldService`、`World`、`Shard`、`Entity`、`Migration` | `zero-world/src/main/java/group/zn/zero/world` | 仅依赖 `zero-actor`；禁止 RPC、数据库、缓存、网络、执行器和高基数指标；不包含跨进程迁移 |

## 4. 游戏业务抽象

| 模块 | 职责 | 核心入口 | 常见修改位置 | 禁止依赖与主要风险 |
| --- | --- | --- | --- | --- |
| `zero-game` | 游戏请求上下文、执行域和 Actor 网关 | `GameRequestContext`、`GameExecutionDomain`、`GameActorGateway` | `zero-game/src/main/java/group/zn/zero/game` | 不引入具体存储或传输；上下文、lane 映射和 TraceId 传播变化需跨模块验证 |
| `zero-player` | 登录、精确会话登出、UID 解析、玩家资料与在线玩家服务抽象 | `PlayerService`、`LocalPlayerService`、`PlayerLogoutRequest`、`MissingPlayerPolicy`、`PlayerUidResolver` | `zero-player/src/main/java/group/zn/zero/player` | 玩家状态绑定 player actor；sessionId 防止旧连接删除替换会话；默认拒绝缺失档案，原型显式选择 CREATE_DEFAULT；认证授权由业务提供 |
| `zero-scene` | 场景进入、离开、实体状态、位置与移动抽象 | `SceneService`、`LocalSceneService`、`SceneEnterRequest`、`SceneMoveRequest` | `zero-scene/src/main/java/group/zn/zero/scene` | 场景状态默认绑定 scene actor；AOI、跨服迁移和广播一致性不应塞入本地参考实现 |
| `zero-aoi` | 单 owner 内存 AOI 索引、可见集合与可见性事件最小运行时 | `AoiIndex`、`InMemoryAoiIndex`、`VisibilityEvent` | `zero-aoi/src/main/java/group/zn/zero/aoi` | 仅为 local/minimum-slice；禁止具体中间件、网络写入和跨服迁移；生产容量未证明 |
| `zero-state-sync` | snapshot/delta envelope、状态版本与 baseline 重同步判定最小运行时 | `SyncEnvelope`、`InMemoryStateSync` | `zero-state-sync/src/main/java/group/zn/zero/statesync` | 仅为 local/minimum-slice；不冻结客户端协议、不负责业务规则或跨服一致性 |
| `zero-frame-sync` | 固定帧时钟、输入收集、迟到/缺失策略、确定性帧提交与广播的本地最小运行时 | `FrameMatchRuntime`、`FrameMatchConfig`、`FrameInput`、`FrameCommitted` | `zero-frame-sync/src/main/java/group/zn/zero/framesync` | 仅为 local/minimum-slice；单 lane、有界输入；接入层断线必须调用 leave(uid)；不包含 rollback、回放、真实传输或生产容量证明 |
| `zero-npc` | Actor-owned zone NPC lifecycle、behavior state 与 bounded tick/degradation 的本地最小运行时 | `LocalNpcZone`、`ZoneActor`、`NpcSnapshot`、`TickBudget`、`BehaviorAdapter` | `zero-npc/src/main/java/group/zn/zero/npc` | 仅依赖 `zero-actor`；禁止 Starter、网络、中间件、线程池和阻塞 adapter；仅证明 local/minimum-slice，不包含完整 AI、持久化、跨服或生产容量 |
| `zero-ranking` | 线程安全的本地排行榜与赛季：锁内状态提交、锁外通知；顺序业务显式选择 Actor lane | `RankingService`、`LocalRankingService`、`RankSnapshot` | `zero-ranking/src/main/java/group/zn/zero/ranking` | 仅依赖 `zero-actor`；无内部 executor、middleware 或具体 adapter；local slice，不包含 Redis、跨服或奖励发放 |
| `zero-room` | 本地内存房间生命周期、成员状态、广播、结算与有界事件历史 | `LocalRoomService`、`RoomState`、`RoomSnapshot`、`RoomEventHistory` | `zero-room/src/main/java/group/zn/zero/room` | 仅依赖 `zero-actor`；通知失败不回滚提交，历史可配且显式标记缺口；不包含持久日志、匹配或跨服 |
| `zero-logic` | 逻辑会话和端到端业务夹具 | `LogicSession`、`LogicSessionManager`、`LocalLogicExample` | `zero-logic/src/main/java/group/zn/zero/logic` | 主要用于本地示例与测试夹具，不应成为具体游戏业务的巨型公共模块 |

## 5. 网络、RPC、数据与缓存

| 模块 | 职责 | 核心入口 | 常见修改位置 | 禁止依赖与主要风险 |
| --- | --- | --- | --- | --- |
| `zero-net` | TCP / UDP / HTTP、frame 编解码、IO 资源拥有权、连接生命周期、准入与限流 | `NettyIoResources`、`NettyTcpServer`、`NettyUdpServer`、`NettyHttpServer`、`ProductionNetworkLifecycle` | `zero-net/src/main/java/group/zn/zero/net` | IO 线程禁止执行阻塞业务；借用组由组合根关闭，各 server 独立关闭连接和出站预算 |
| `zero-rpc-common` | 最小 RPC 调用契约与调用模式 | `RpcClient`、`RpcCallOptions`、`RpcCallMode`、`RpcResult` | `zero-rpc-common/src/main/java/group/zn/zero/rpc/common` | 禁止绑定 Kafka 或服务发现；公共调用语义变化会影响所有 transport |
| `zero-rpc` | 服务描述、绑定、路由、codec、transport SPI 与远程 Actor 桥接 | `RpcClientFactory`、`RpcServiceBinder`、`RpcTransport`、`RpcRemoteActorGateway` | `zero-rpc/src/main/java/group/zn/zero/rpc` | 禁止反向依赖 `zero-rpc-kafka` 或 Nacos；超时、幂等、路由和 request/response 语义属于高风险契约 |
| `zero-rpc-kafka` | Kafka RPC envelope、gateway、pending 请求与超时轮 | `KafkaRpcAdapter`、`KafkaRpcClientFactory`、`ApacheKafkaRpcMessageGateway`、`KafkaRpcSettings` | `zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka` | 必须保持 `correlationId`、`replyTopic`、`traceId`、`timeoutAt` 等语义；关注资源关闭、积压和过期拒绝 |
| `zero-data` | Repository / DataService、对象映射、持久化调度与线程绑定 | `Repository`、`CrudRepository`、`DataService`、`PersistenceManager` | `zero-data/src/main/java/group/zn/zero/data` | 禁止依赖具体数据库；存储格式、版本 CAS、脏数据追踪和落库失败策略不可隐式改变 |
| `zero-data-mongo` | MongoDB envelope 存储与健康检查 | `MongoDataAdapter`、`MongoDriverEnvelopeStore`、`MongoDriverSettings` | `zero-data-mongo/src/main/java/group/zn/zero/data/mongo` | 驱动配置、超时、对象映射和版本一致性需真实环境验证 |
| `zero-data-redis` | Redis 数据 envelope、追加式 journal 与健康检查 | `RedisDataAdapter`、`RedisDriverEnvelopeStore`、`RedisDriverClientFactory` | `zero-data-redis/src/main/java/group/zn/zero/data/redis` | 不与缓存职责混淆；连接、Lua / CAS、key 规范和故障恢复需单独验证 |
| `zero-data-postgresql` | PostgreSQL JDBC envelope 存储与健康检查 | `PostgresqlDataAdapter`、`PostgresqlDriverEnvelopeStore`、`JdbcConnectionFactory` | `zero-data-postgresql/src/main/java/group/zn/zero/data/postgresql` | 关注连接管理、事务、SQL 方言、超时和资源关闭 |
| `zero-cache` | L1 / L2 cache-aside、加载协调、版本、失效与统计 | `CacheService`、`LayeredCacheService`、`CacheStore`、`RedisDistributedCacheService` | `zero-cache/src/main/java/group/zn/zero/cache` | 缓存不能成为主数据真相；key、TTL、版本、击穿/穿透保护和降级语义变化需并发验证 |
| `zero-discovery` | 中立服务发现接口、模型和本地实现 | `ServiceDiscovery`、`InMemoryServiceDiscovery` | `zero-discovery/src/main/java/group/zn/zero/discovery` | 不依赖 Nacos 或 RPC |
| `zero-rpc-discovery` | 服务发现到 RPC 的中立映射 | `ServiceDiscoveryRpcServiceResolver`、`NacosRpcMetadataMapper` | `zero-rpc-discovery/src/main/java/group/zn/zero/rpc/discovery` | 不引入 Nacos SDK；mapper 名称保留既有命名 |
| `zero-discovery-nacos` | Nacos 服务发现和配置映射 | `NacosDiscoveryFactory`、`NacosDiscoveryAdapter`、`NacosNamingServiceFactory` | `zero-discovery-nacos/src/main/java/group/zn/zero/discovery/nacos` | 单向依赖中立 discovery；注册、续约、摘除、鉴权和网络故障需真实环境验证 |

## 6. 日志、监控、GM 与热更

| 模块 | 职责 | 核心入口 | 常见修改位置 | 禁止依赖与主要风险 |
| --- | --- | --- | --- | --- |
| `zero-log` | 结构化日志、字段校验、敏感字段策略、标识符脱敏和 sink 抽象 | `LogAppender`、`LogPipeline`、`LogSink`、`LogRecordValidator` | `zero-log/src/main/java/group/zn/zero/log` | 业务组件优先依赖 `LogAppender`，只有顶层装配持有 terminal `LogSink`；禁止泄漏凭据和原始敏感标识 |
| `zero-monitor` | 指标注册、Prometheus 导出、Grafana 模板、告警规则与系统指标 | `MonitorRuntime`、`MetricRegistry`、`PrometheusExporter`、`AlertEvaluator` | `zero-monitor/src/main/java/group/zn/zero/monitor`、`config/monitoring` | 标签必须有界，避免高基数；告警阈值需按工作负载校准，不应声称默认值适合所有生产环境 |
| `zero-gm` | GM DSL、命令注册、dry-run、审批状态、审计归因与执行结果 | `GmCommandDsl`、`GmCommandExecutor`、`GmAuditHook`、`GmResponse` | `zero-gm/src/main/java/group/zn/zero/gm` | 真实后台需自行接入认证、RBAC、IP 白名单和审批持久化；所有操作必须可审计且失败不可伪装成功 |
| `zero-gm-rest` | 上层 REST transport boundary：请求预算、最小路由、应用身份注入和稳定错误响应 | `GmRestTransportAdapter`、`GmIdentityProvider`、`GmTransportRequest` | `zero-gm-rest/src/main/java/group/zn/zero/gm/rest` | 只提供适配边界，不内置身份、JWT、RBAC、审批、HTTP server 或持久化；依赖 `zero-gm` 与 `zero-net`，不能反向改变 GM 核心 |
| `zero-hot-update` | 热更等级、请求与边界模型 | `HotUpdateLevel`、`HotUpdateRequest` | `zero-hot-update/src/main/java/group/zn/zero/hotupdate` | 当前模块不等于完整热更运行时；类结构变更、ClassLoader、集群同步与回滚必须独立设计 |

## 7. 装配、示例与性能证据

| 模块 / 目录 | 职责 | 核心入口 | 常见修改位置 | 禁止依赖与主要风险 |
| --- | --- | --- | --- | --- |
| `zero-server-starter` | 全量本地组合、配置热更和受管调度 | `LocalRuntime`、`LocalRuntimeBuilder`、`ZeroServerApplication`、`ZeroServerTcpApplication` | `zero-server-starter/src/main/java/group/zn/zero/starter` | 便利组合包并非最小依赖入口；默认路径无外部服务 |
| `zero-server-starter-production` | 全量生产便利组合 | `ZeroProductionRuntimeBuilder`、`ZeroProductionRuntimeFactory` | `zero-server-starter-production/src/main/java/group/zn/zero/starter/production` | 委托独立 ProductionAssembly 和各集成模块；选择少数组件不裁剪这个全量包 |
| `zero-benchmarks` | JMH 微基准与方向性成本证据 | `LogFieldsBenchmark`、`MetricLabelsBenchmark`、`ProductionNetworkObserverBenchmark` | `zero-benchmarks/src/main/java/group/zn/zero/benchmark` | 只通过 `-Pbenchmarks` 启用；结果不能直接解释为生产吞吐或容量承诺 |
| `examples/` | 可独立构建运行的最小示例 | 各示例 `*Application` 与 README | `examples/<example>` | 示例默认使用 local/prototype 能力；生产部署必须显式配置安全、容量和真实 Adapter |
| `templates/` | 七类业务模板和按需 runtime 模板 | `RuntimeAssembly.java`、`README.md.tpl`、`BUSINESS_GUIDE.md.tpl`、`COMPONENTS.md.tpl` | `templates/<template>`、`zero-codegen/.../scaffold` | 组件选择决定依赖与装配；协议生成器仅在构建插件中；批量验证生成与依赖闭包 |
| `scripts/` | 本地诊断、生成、结构检查、统一验收与批量验证 | `ZeroLocalDoctor.java`、`ZeroStage0Acceptance.java`、`NewLocalGame.java`、`RunLocalPrototype.java`、`VerifyLocalScaffolds.java` | `scripts/*.java` | 脚本不应依赖私有协作材料或本机绝对路径；生成操作必须限定输出目录并避免覆盖未知文件 |

### 7.1 可独立消费的装配模块

当前根 Reactor 共 61 个默认模块（benchmark 仅由显式 profile 启用）。新增集成层放置端口能力键和 provider；端口与纯 Adapter 不反向依赖 runtime。

| 模块 | 职责 / 入口 |
| --- | --- |
| `zero-runtime-bootstrap` | 配置、受管执行器与最小组合入口 `RuntimeBasics` |
| `zero-runtime-event`、`zero-runtime-actor`、`zero-runtime-protocol` | 事件、Actor、协议的本地集成 |
| `zero-runtime-rpc`、`zero-runtime-data`、`zero-runtime-cache` | RPC、持久化/命名 Repository 来源及角色目录、缓存集成 |
| `zero-runtime-log`、`zero-runtime-monitor`、`zero-runtime-discovery` | 日志、监控、本地发现集成 |
| `zero-runtime-production` | 无驱动依赖的 `ProductionAssembly`、配置、启动预算、诊断与周期 resilience 契约 | `ProductionAssembly`、`ProductionAdapterLifecycle`、`ProductionStartupBudget`、`RuntimeAdapterHealth`、`RuntimeAdapterRecovery`、`RuntimeAdapterBudget`、`RuntimeAdapterResilience` |
| `zero-runtime-kafka`、`zero-runtime-mongo`、`zero-runtime-redis` | 各自 Adapter provider、健康与资源管理 |
| `zero-runtime-postgresql`、`zero-runtime-nacos`、`zero-runtime-net` | 各自 Adapter provider、健康与资源管理 |

这些集成模块不能依赖 Starter 或无关的真实 Adapter。完整选择示例见[按需装配指南](guides/modular-composition-guide.zh-CN.md)。

`zero-runtime-net` 的生命周期 provider 仅要求 `RuntimeBasics.CONFIG` 与 `RuntimeBasics.EXECUTORS`；中立能力模型与实际 descriptor 保持一致。该模块不传递依赖 runtime-log/runtime-monitor；可选 `ProductionNetworkTelemetryObserver` 使用 optional 的 zero-log/zero-monitor，由消费者显式声明。常改入口为 `NetworkRuntime.module`、`ProductionNetworkProvider`、`ProductionNetworkSettings` 和 `ProductionNetworkLifecycle`；安全链在生命周期组合根统一包装，防止直接构造时漏执行。迁移与策略责任见[最小网络迁移](migrations/20260928-production-network-minimal-customization.md)。

`ProductionAssembly.builder(profile, config)` 接受 standalone/external-test/production，`plan()` 在不创建资源的前提下返回实际 provider 图。脚手架在 `ScaffoldComponents` 选择现有集成，在 `ScaffoldConfiguration` 生成所选 Adapter 的开关及外部配置样例。示例 `examples/modular-composition/center-logic` 只依赖 bootstrap/RPC；TCP 示例的执行器由 bootstrap 管理、codegen 只存在于构建插件。脚手架 `net` 选择网络生命周期 provider，限流、安全链、observer 与心跳检查均由应用按需显式接入；未注入限流器使用 permit-all，未注入 observer 使用 no-op。`runtime + net` 仅依赖 `zero-net` / `zero-runtime-net` 及其闭包，`local + net` 的 TCP 示例额外依赖 `zero-server-starter`。网络监听与生产安全策略仍由应用显式接入。入口和限制见[场景指南](quickstart.zh-CN.md)。

`zero-data/repository` 的 `RepositoryDefinition`、`RepositoryFactory`、`RepositorySource`、`RepositoryCatalog` 形成中立业务入口；工厂按需创建既有 envelope Repository。`examples/repository-composition` 演示同一余额业务切换四种来源，生命周期和执行域要求见 [Repository 指南](guides/repository-composition-guide.zh-CN.md)。`VerifyGeneratedCompositions.java` 检查生成消费者的 Maven 依赖、SDK 缺席与自定义实现选择。

## 8. 中立装配内核与独立集成

`zero-runtime` 已进入根 Reactor 和 BOM，提供中立 API/SPI、显式 catalog/selection、最小依赖闭包、typed config、确定性拓扑、build/start 双资源账本、独立 assembly/startup deadline、启动健康、single-use 生命周期、安全诊断和共享 capability model。`RuntimeComposition` / `RuntimeModule` 为显式组合提供轻量入口；具体端口 key 由对应集成模块绑定，中立内核不包含真实 Adapter provider。

当前依赖方向：

```text
业务应用 -> zero-runtime-bootstrap + 所选集成模块 -> zero-runtime -> zero-core
Local Starter -> 本地集成模块 -> 中立端口
Production Starter -> zero-runtime-production + 各真实集成模块 -> 对应 Adapter
zero-codegen -> RuntimeCapabilityModel -> zero-runtime
zero-runtime -X-> 业务模块、Starter、Netty、Kafka、Nacos、数据库驱动
```

首版 API/SPI 与引擎位于同一模块并按 `api`、`spi`、`assembly`、`config`、`health`、`diagnostics`、`capability` 包隔离。Local Starter、示例、模板和 `zero-codegen` 已切换到该内核及共享模型；1D 已接入 Kafka、MongoDB、Redis、PostgreSQL、Nacos 与 network provider，并删除包内迁移 bridge。业务只通过中立 typed capability 访问运行能力。

完整的方案比较、公共契约、复杂度分层和破坏性迁移计划见[模块化运行时装配设计](reference/modular-runtime-assembly.zh-CN.md)。1D 五项契约已经确认并完成代码收敛；真实外部中间件、容量、恢复与长稳证据仍未完成，因此不得描述为 production ready。

## 9. 常见改动的落点

| 需求 | 首选位置 | 必须联动检查 |
| --- | --- | --- |
| 生命周期、配置、错误码基础契约 | `zero-core` | 所有调用方、Starter、异常与文档 |
| 事件派发、优先级、重试、死信 | `zero-event` | 顺序、幂等、异常、TraceId 与队列压力 |
| 协议字段、ID、frame、codec | `zero-protocol` / `zero-codegen` | 多语言生成物、兼容性、网络/RPC 和性能 |
| Actor 调度、lane、跨 Actor 路由 | `zero-actor` / `zero-game` | 线程归属、背压、远程路由、指标与 JMH |
| 登录、玩家、场景业务抽象 | `zero-player` / `zero-scene` | Actor 绑定、持久化、缓存、断线恢复与示例 |
| TCP / UDP / HTTP 或连接治理 | `zero-net` | IO 线程、buffer、协议、限流、日志和容量 |
| RPC 公共语义 | `zero-rpc-common` / `zero-rpc` | Kafka Adapter、服务发现、超时、幂等与兼容性 |
| Kafka transport 实现 | `zero-rpc-kafka` | broker external tests、积压、资源关闭与观测 |
| Repository / 映射 / 持久化 | `zero-data` | 三个数据 Adapter、存储兼容、线程绑定和失败恢复 |
| 缓存策略 | `zero-cache` | Redis、版本、击穿/穿透、降级与一致性 |
| 结构化日志或指标字段 | `zero-log` / `zero-monitor` | 脱敏、基数、错误码、审计、告警与基准 |
| GM / 审批 / 审计 | `zero-gm` | 鉴权、权限、dry-run、归因、失败状态与安全测试 |
| 本地或生产装配 | 对应 `zero-runtime-*` 集成模块；全量组合再改 Starter | 独立消费者依赖、生命周期顺序、回滚、配置泄漏、资源关闭和示例 |
| 中立组件装配契约 | `zero-runtime` | 依赖图、选择来源、配置 schema、回滚、健康、诊断脱敏和 API/SPI 编译面 |
| 新增正式模块或改变依赖方向 | 根 `pom.xml`、`zero-bom`、本文 | 架构守卫、CI、README、兼容性和迁移说明 |

## 10. 变更检查

修改模块结构或依赖后，至少执行：

```powershell
java scripts/ZeroArchitectureGuard.java
mvn -B -ntp test
mvn -B -ntp -Pquality verify
```

涉及 integration 测试时再执行 `mvn -B -ntp -Pintegration-tests verify`；涉及真实 Kafka、MongoDB、Redis、PostgreSQL 或 Nacos 时，使用明确隔离的测试环境运行 `-Pexternal-tests`，记录组件版本、配置来源与清理方式。公共 API、协议、线程模型、RPC、存储、权限、日志字段和热更机制的变化应先通过 GitHub Design Proposal 说明设计、兼容、性能、安全和验证边界。


## 2026-09-17 报告核实修订

帧同步共享调度器消息路由入口新增 zero-frame-sync/.../FrameDispatchRegistry.java，职责仅为每个调度器安装无状态路由，不创建线程、不改变模块依赖。报告核实修复索引见 reports/bug-analysis-verification-20260917.zh-CN.md。

## 2026-09-23 性能路径入口

- `zero-ranking/RankIndex`：包内跨度跳表，`LocalRankingService` 持锁同时更新 UID/排名索引；不向业务暴露容器。
- `zero-actor/scheduler`：`ActorSchedulerConfig`、`ActorSchedulerStatistics` 与 `OrderedActorHandlers`；Local 委托同一 Executor 调度内核，仍由调用线程推进，不依赖网络/监控 Adapter。
- `zero-protocol`：`ProtocolFrame` 长度/只读视图、`ProtocolFrameCodec` 缓冲入口；Netty 依赖仍仅位于 `zero-net`。
- `zero-net`：`NetworkTuning`、`NettyTransportFactory`、`NettyZeroBuffer`、`NettyOutbound`/`OutboundBudget`，分别负责配置、transport、缓冲桥和出站准入。EPOLL 类依赖及显式 `linux-native` 运行库只在本模块引入。
- `zero-net/netty/NettyIoResources` 统一创建和关闭 IO 组；`NettyServerResources` 负责单次启动的监听/子连接回收。所有服务器默认独占，也可显式借用兼容组。单帧出站完成协调同时等待写 promise 和 flush 屏障。
- `zero-runtime-net/NetworkRuntime.ioResources` / `ioModule` 是可选装配入口，使用 `ResourceRegistrar` 执行回滚和关闭；中立能力 `zero.net.io-resources` 位于 runtime，实际实现仍在 net。依赖维持 `runtime-net -> net/runtime-production`，bootstrap/core 不依赖 Netty。示例及拥有权见[迁移说明](migrations/20260923-performance-third.md)。
- `zero-aoi`：`observe/forgetObserver` 管理观察生命周期；`zero-frame-sync` 使用有序参与者目录，公开 batch 不复用。
- `zero-scene`：外层并发目录与私有 `SceneState` 分离；内部实体表只在 Scene Lane 读写。
- `zero-benchmarks` 的精确直接模块依赖为 protocol/ranking/actor/cache/aoi/frame-sync/scene/log/monitor/net/server-starter-production，架构守卫同步此集合；运行时禁止反向依赖 JMH。`benchmark/performance` 与 `scripts/performance/RunTcpLoad.ps1` 是本地测量入口。

`zero-runtime-actor/ActorRuntime.module(config)` 装配显式 Actor 预算，并在 runtime 资源账本登记调度器，使排队消息在执行器关闭前收到失败。

Scheduler 的单次 timer 登记由 `zero-server-starter/.../scheduler/SchedulerTimerRegistration` 管理；`LocalManagedScheduler` 在提交前发布登记，回调按身份移除，避免迟到 future 覆盖后续任务。它仅拥有 timer future，不创建业务线程，也不改变核心 scheduler SPI。


## 2026-10-01 可靠性与维护任务入口

| 入口 | 职责与依赖 | 风险边界 |
| --- | --- | --- |
| `zero-data/persistence/durable` | `DurablePersistenceService`、`LocalDurableJournal`、`DurableRecord`；依赖中立 Envelope Store、绑定执行器及外部 IO Executor | 本地耐久意图/CAS 恢复；不依赖具体驱动，不承诺多进程 fencing |
| `zero-runtime-data/DurablePersistenceRuntime` | 按需装配 `zero.data.durable`，新增 `zero-runtime-data -> zero-runtime-bootstrap` 依赖 | 运行时拥有日志/服务，应用拥有目标 Store，关闭顺序必须正确 |
| `zero-runtime-bootstrap/PeriodicAsyncTask` | 单次在途的周期 IO 维护，依赖 `ZeroRuntimeExecutors` 的受管计时资源 | timer 不运行远程 IO，stop 等待在途任务 |
| `zero-runtime-redis/ProductionRedisCacheProvider` | 新增 EXECUTORS 依赖，按生命周期重试缓存写回 | 队列有界但非耐久，不能作为主数据提交确认 |
| `zero-runtime-kafka/ProductionKafkaRpcProvider` | 新增 EXECUTORS 依赖，组合根向 Kafka 时间轮注入 timer | `zero-rpc-kafka` 不反向依赖 bootstrap，Adapter 不关闭外部 timer |
| `zero-log -> zero-monitor` | 已有同层单向依赖，用于日志指标观测 | monitor 不得反向依赖 log，不将具体中间件带入核心 |

修改入口还包括 World 异步方法、Room 发布视图和 Player 远程 IO 构造器。核心禁止依赖驱动的方向不变；标准能力模型与 Provider 依赖同步。迁移见 [可靠性说明](migrations/20261001-audit-reliability.zh-CN.md)。

## 2026-10-08 B4 审计入口

| 模块与入口 | 单一职责及常见修改点 | 风险边界 |
| --- | --- | --- |
| `zero-net` / `NetworkTuning`、`NettyFrameChannelHandler` | TCP 顺序入站计数/字节准入及物理完成回收；出站诊断在 `NettyOutbound` | 超限关闭；IO 资源由 `NettyServerResources`/`NettyIoResources` 负责，不关闭借用组 |
| `zero-rpc-kafka` / `KafkaRpcAdapter`、`KafkaRpcEnvelopeCodec` | 调用前期限检查、拒绝观测、32MiB 整包预算 | 不撤销已开始业务；codec 不能限制 Kafka 已分配的原始批次 |
| `zero-discovery-nacos` / `NacosDiscoveryAdapter` | TTL 缓存失效代际与最近 256 条监听故障 | SDK IO 不持缓存锁；仍是最终一致路由 |
| `zero-cache` / `CacheKeyOperations`、`CacheRecoveryState` | 同键写物理屏障与独立观察期限；后端成功代际及外部恢复凭据 | 超时结果未知；外部故障不能靠 L1/L2 无关成功清除 |
| `zero-data/envelope` / `KeyTypeResolver`、`ZeroDataEnvelope.contentEquals` | 键 SPI 继承绑定解析、完整内容比较；由 entity codec/durable intent 使用 | 未解析键拒绝；不改存储字节或 record 相等语义 |
| `zero-net-kcp` / `KcpResponseBatch`、`KcpReceiveTracker` | 无递归响应逐帧本地接收；有界接收元数据与静默屏障 | 不等远端 ACK；不完整接收阻止迁移；FEC 回调内关闭精确回收 |

没有新增跨模块依赖，core 继续禁止依赖具体驱动。Redis wrapper 转发缓存外部恢复入口；runtime-kcp 保留新配置字段。详细 API 与行为见 [B4 迁移](migrations/20261008-audit-b4.zh-CN.md)。

## B6 工具入口与修改位置（2026-10-08）

`zero-codegen` 的 `CodegenExecution` 共享 CLI/GUI 模式；`ProtocolNames`、`ProtocolTypeBudget` 和 `TargetNames` 管输入边界，`OutputPlans` 管显式输出根，`ManagedOutput` / `OutputTransaction` 管摘要、锁与 schema 2 恢复。`ScaffoldTemplateTokens` 负责单次模板替换，`ScaffoldCreationTransaction` 负责首建提交，与 `ScaffoldUpgradeService` 共用工程锁。游戏运行时依赖方向不变，不引入中间件依赖。

脚本修改入口：`scripts/ResolveCodegen.ps1` 解析精确构件；`VerifyCodegenModes.java` 验证模式退出；`VerifyAdapterCompositions.java` 验证新报告实际覆盖。公共单后端生成必须使用受管计划。恢复元数据格式及工具 API 变更风险见 [B6 迁移](migrations/20261008-audit-b6.zh-CN.md)。
