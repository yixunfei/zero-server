# 通用适配、可定制与低心智开发目标复审

日期：2026-10-12。版本：`0.1.2-SNAPSHOT`。审查基线：`b61a8bf3377662211e4cbd14b7bcfb7dcdee9ac6` 及当前工作区。范围覆盖 61 个默认 Maven 模块、可选 `zero-benchmarks`，并检查脚手架、公开示例、文档及验收入口。

用户确认只审查、保存报告，由三个子 Agent 分组分析、主会话复核；本轮不修复业务代码、不发布。目标边界按仓库定位理解为各种游戏类型及部署规模；非游戏服务可复用中立基础设施，但本次不把完整 Web 应用框架能力列为必备项。

同日[二轮复查](20261012-product-goal-recheck.zh-CN.md)补充了反证与五组本地复现：D04/D05 提升证据，新增缓存无效选项与排行榜生命周期问题，并校正 DSL 路径、监控/发现已有能力及默认装配缺口的范围。当前采用两份报告时，以二轮范围说明为准。

## 1. 整体判断

**当前已经是具备实质实现的模块化游戏服务端底座；距离“用户主要写业务，框架承担大部分架构与通用模块设计”，最大的差距在可复用业务组合、默认运行闭环和持续接入验收。**

基础设施层的“可选择、可替换”明显强于游戏业务层的“可直接采用、可自然扩展”。许多机制已经存在，但用户仍需自行连接协议返回、身份上下文、线程切换、状态生命周期、持久化、可靠通知、发现路由及运营工具。继续单纯增加模块数量，未必能显著缩短这段距离。

| 目标 | 当前判断 | 主要依据与距离 |
| --- | --- | --- |
| 按需选择、依赖裁剪 | 较好 | 中立 runtime、独立集成模块、显式选择和依赖门禁已工作；全量 Starter 与最小入口已区分 |
| 基础设施高度定制 | 较好但不均匀 | provider/replace、具名 Repository、codec/transport 等已有；缓存高级能力、监控 registry、恢复策略等仍有具体实现或固定默认接线 |
| 各类游戏适配 | 原语覆盖较广，正式组合不足 | Room/Scene/AOI/Frame/NPC/Ranking/World 已有本地实现；规则扩展、业务数据及真实网络流程未形成统一使用路径 |
| 开箱即用 | 本地入门可用，外部模式有断点 | 本轮 Stage 0 quick 全通过；Repository 外部示例失败，Kafka 双 JVM 示例缺少安全接线 |
| 低心智、专注业务 | 部分达到 | 新业务样例有进步，但高级流程仍要求用户理解多个 provider、执行域和失败语义，再自行写通用编排 |
| 单机到分布式 | 基础机制有，业务连续性不足 | ownership/远程 Actor/真实 Adapter 已有；同一玩法代码切换部署并完成恢复的整条路径未交付 |
| 开箱运营 | 组件有，组合待补 | 日志、Prometheus endpoint、GM、热配置均有；采样、监听、身份、持久审计、排空和发布仍需要手工组装 |

这里不提供完成百分比：本地原型、多进程业务和生产运营的完成标准不同，简单平均会掩盖关键断点。`productionReady=false` 仍合理，但不应把这个标签作为降低公开示例可运行性或局部契约一致性要求的理由。

## 2. 本轮已核实的进展

以下能力已经存在，不应再次作为“尚未实现”安排重复建设：

- `RuntimeModule.value/component`、延迟工厂、资源登记、依赖规划、配置来源诊断及共享健康快照。
- 最小 `runtime` 生成项目、独立 Wrapper、组件依赖闭包安装、显式 catalog、升级 ownership manifest 和事务回滚。
- RepositoryFactory/Catalog、逻辑 role 到具名 source 的路由，统一 envelope/CAS 与 MongoDB、Redis、PostgreSQL 真实实现；PostgreSQL runtime 已使用连接池。
- Actor 队列预算和超时隔离、事件失败聚合/有界死信，以及显式 `RetryingEventHandler`。不能把事件模块写成完全没有重试。
- Adapter 在途预算、退化准入、周期探测、readiness 和恢复接线；Redis data/cache 共享状态及真实恢复测试入口已经存在。
- ownership 的 lease/epoch fencing、状态 CAS、幂等及 PostgreSQL 事务实现；已有双进程外部测试源码和历史证据。
- KCP 预设、保护/FEC、所有权与迁移；不能沿用“尚未实现 KCP”或“完全没有跨节点机制”的旧判断。
- `module-access` 全模块接入卡、`module-recipes` 七个消费者、业务奖励切片和玩家多角色示例。示例缺口应重新定义为组合深度和部署真实性。

当前 [能力矩阵](../capability-matrix.zh-CN.md) 开头仍以 9 月 28 日为核对基准，其中周期健康、独立预算等缺口已落后于 10 月实现；模块图顶部则有新进展。用户需要同时拼接多份文档才能判断现状，本身就是心智负担。

## 3. 先处理的确定问题

优先级按本次目标评估：P1 为公开使用路径中断或重要运行错误；P2 为重要契约、长期运行和可维护性问题。下表中的“静态确认”没有被包装成外部环境实测。

| 编号 | 级别 / 证据 | 问题及影响 | 建议与验收 |
| --- | --- | --- | --- |
| D01 | P1 / 本轮复现 | Repository 示例外部分支显式传入 direct IO，被 ProductionAssembly 拒绝，用户按说明切换真实数据库无法启动 | 采用默认受管 IO 或显式非内联 IO；公开组合根的 local/mongo/redis/postgresql 路径都应检查执行域与启动阶段 |
| D02 | P1 / 源码确认，相关安全单测本轮通过 | center-logic-kafka 的 client 没有 signer/context，server 没有 verifier；当前 Kafka 接收默认拒绝，因此 broker 正常也无法完成注册 | 给示例提供显式 service identity 配方；保留默认拒绝；增加无 broker 的两端身份接线回归，再验收真实双 JVM |
| D03 | P1 / 本轮复现 | NPC 每次从固定顺序队首开始 tick，预算不足会永久饿死后部 NPC；候选全量复制还发生在计时开始前 | 轮转/续扫并把候选选择计入预算；100 NPC、预算 3 的多轮公平性、增删、零预算与开销边界都应验收 |
| D04 | P2 / 静态确认 | 便捷 InMemoryCrudRepository 对不存在 ID 无条件以 version=1 插入，删除后保存旧快照会复活记录；标准 local envelope/真实后端要求 version=0 才插入 | 统一便利内存与正式仓库 CAS 契约；用同一套插入、删除后旧写、负版本、溢出及并发测试验证 |
| D05 | P2 / 静态确认 | World/Ranking 的字符串 ID→序号历史目录只增不减，收据归档或赛季退役没有回收它们；部分拒绝请求也先登记 ID | 开发阶段优先统一显式序号入口；若保留字符串入口，提供有界目录和旧请求拒绝语义；超过容量 10 倍的 churn 后核对所有索引 |
| D06 | P2 / 静态确认 | NPC 决策异常被隔离，但事件/指标没有携带原始原因或 ErrorCode，默认路径难以诊断业务错误 | 增加统一失败观察端口，保留单 NPC 隔离；日志能关联 cause/trace，其他 NPC 继续运行 |

具体源码与证据：

- D01：[RepositoryCompositionApplication.java](../../examples/repository-composition/src/main/java/group/zn/zero/examples/repository/RepositoryCompositionApplication.java) 第 60 行；[ProductionAssembly.java](../../zero-runtime-production/src/main/java/group/zn/zero/runtime/production/ProductionAssembly.java) 第 177 行；[RuntimeAdapterIo.java](../../zero-runtime-production/src/main/java/group/zn/zero/runtime/production/RuntimeAdapterIo.java) 第 68 行。本轮配置 `ZERO_REDIS_URI=redis://127.0.0.1:1`，执行示例 `compile exec:java -Dexec.args=redis`，在连接前即报 `direct remote IO is unsupported`，exit 1。Mongo/PostgreSQL 分支共享错误组合根，但本轮只执行 Redis 分支。
- D02：[CenterMain.java](../../examples/modular-composition/center-logic-kafka/center/src/main/java/group/zn/zero/examples/centerlogic/kafka/center/CenterMain.java) 第 38–42 行；[LogicMain.java](../../examples/modular-composition/center-logic-kafka/logic/src/main/java/group/zn/zero/examples/centerlogic/kafka/logic/LogicMain.java) 第 30–34 行；[KafkaRpcAdapter.java](../../zero-rpc-kafka/src/main/java/group/zn/zero/rpc/kafka/KafkaRpcAdapter.java) 第 61、530 行；[RpcClientFactory.java](../../zero-rpc/src/main/java/group/zn/zero/rpc/client/RpcClientFactory.java) 第 358 行。脚本直接启动两个 Main，没有补安全配置的共享启动器；`KafkaRpcAcknowledgementTest.defaultReceiverFailsClosed` 已在本轮默认测试执行。未运行真实 broker 或双 JVM 示例。
- D03、D06：[LocalNpcZone.java](../../zero-npc/src/main/java/group/zn/zero/npc/LocalNpcZone.java) 第 28、119–124、140–146 行。独立 probe 实测两个 NPC、10 tick、budgetOps=1：调用序列为 `first` 重复 10 次，`secondNpcDecisions=0`。
- D04：[InMemoryCrudRepository.java](../../zero-data/src/main/java/group/zn/zero/data/repository/InMemoryCrudRepository.java) 第 57–59、198 行，对照 [InMemoryEnvelopeStore.java](../../zero-data/src/main/java/group/zn/zero/data/envelope/InMemoryEnvelopeStore.java) 第 35 行及 `ZeroDataEnvelopeCrudRepository` 的版本检查。注意 `DataRuntime` 的标准 local source 已使用正确 envelope 契约，不应扩大为所有本地存储都有问题。
- D05：[MigrationRecords.java](../../zero-world/src/main/java/group/zn/zero/world/MigrationRecords.java) 第 18、42、58 行；[LocalRankingService.java](../../zero-ranking/src/main/java/group/zn/zero/ranking/LocalRankingService.java) 第 29、31、281、302、327 行。本轮未额外运行长期内存增长实验。

这些问题应建立独立修复任务；它们不能由新增架构抽象或更多说明文档替代。

## 4. 距离产品目标的主要缺口

### G01 / P1：场景脚手架应直接复用正式模块

除已改进的 local 玩家/场景模板外，六类场景模板仍主要在单个 Application 中定义嵌套 Actor、Store、State 和 BO。例如 room 模板自建 `RoomStore`，scene-sync 模板自建 `SceneStore` 并遍历实体判断可见性，而不是使用 `zero-room` 或 `zero-aoi/zero-state-sync`。

证据：[ScaffoldCatalog.java](../../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ScaffoldCatalog.java) 第 142 行仅为 local 额外声明 player/scene；[room Application 模板](../../templates/room-game-scaffold/Application.java.tpl) 第 294、316、362 行；[scene-sync Application 模板](../../templates/scene-sync-scaffold/Application.java.tpl) 第 272、294、340 行。六类模板还存在同步 `.join()` 的命令分发模式，适合 CLI 演示，不适合作为网络/Actor handler 的默认复制路径。

**后果：模板跑通，不代表正式模块接线跑通；正式模块修复不会自动惠及模板；用户从教学示例晋升到实际游戏时要重新设计一次。**

建议保持源码模块边界，收敛用户入口：生成项目只暴露协议、业务 handler、规则/状态定义和一个组合根。模板直接消费正式模块，示例与模板共用同一场景组装。生成的业务指南不应要求普通应用开发者先了解框架贡献流程或向上游提交 Design Proposal，才能扩展自己的玩法。

验收：七类业务模板分别生成、构建、运行并确认实际依赖/调用对应正式能力；修改一条房间规则或增加一个业务字段，不需要编辑框架调度、Store 或生命周期代码；异步网络入口没有等待同一执行域的 `.join()`。

### G02 / P1：把请求到异步业务结果的接线变成统一默认

推荐 `.si` 入口采用 void BO，请求与 server_to_client 响应为独立消息；旧块式 DSL/公共模型已有同步返回 DTO 的能力，不能简单说“所有 BO 都是 void”。[JavaCodegenRenderer.java](../../zero-codegen/src/main/java/group/zn/zero/codegen/generator/java/JavaCodegenRenderer.java) 第 400–411 行生成同步返回类型，[dispatcher.java.ftl](../../zero-codegen/src/main/resources/codegen/java/dispatcher.java.ftl) 第 42 行调用 BO 后未承载返回结果，dispatch 按低层契约返回是否找到 handler。两条路径缺少的是上层统一异步结果及响应发送组合，不应当作布尔 dispatcher 违反自身契约。

local 模板已用请求级 BO、`Consumer<CompletionStage<?>>` 和独立 Flow 解决异步完成，但这是模板自建桥接：[LocalGameBO 模板](../../templates/local-game-scaffold/LocalGameBO.java.tpl) 第 30、71 行；[LocalGameServer 模板](../../templates/local-game-scaffold/LocalGameServer.java.tpl) 第 292 行在业务完成后回显原 DTO，尚非统一的类型化业务响应。

建议围绕一个真实协议定义标准异步 handler/dispatch 结果和显式请求上下文，使错误码、身份、trace、deadline、取消及响应编码在统一边界处理；保留低层 codec/dispatcher 可独立消费。应先通过实际业务验证后再调整 DSL/API，避免额外叠一层同义框架。

验收：同一个登录或奖励 BO 经 TCP/local 两种入口，返回类型化成功响应和稳定业务错误；延迟完成、超时、断连、取消都有确定结果；业务不自行传结果 Consumer、不重复包装每个异步回调的上下文。

### G03 / P1：交付一个单机与多进程共享业务代码的完整样板

当前 GameActorGateway 支持远程，ownership/PostgreSQL 也有真实机制，但 Player/Scene 在实现内部创建本地 gateway，Room/World/NPC 直接使用本地 scheduler；正式业务尚未连接 checkpoint、owner 接管和恢复。`multi-server-player` 是有价值的本地编排示例，其 [RoleLauncherMain.java](../../examples/multi-server-player/src/main/java/group/zn/zero/examples/multiplayer/RoleLauncherMain.java) 第 24 行仍输出 `rpcHandlers=0`；本地 PlayerLifecycleService 也具有创建线程约束，不能直接当作远程服务 handler。

证据：[LocalPlayerService.java](../../zero-player/src/main/java/group/zn/zero/player/LocalPlayerService.java) 第 157、165 行；[LocalSceneService.java](../../zero-scene/src/main/java/group/zn/zero/scene/LocalSceneService.java) 第 48–50 行；[FailoverCoordinator.java](../../zero-ownership/src/main/java/group/zn/zero/ownership/FailoverCoordinator.java) 第 74 行。RPC/发现接线也不完整：普通 RpcClientFactory 从 descriptor 读取固定 topic/group，并不自动消费 resolver；Nacos 已有默认 1 秒、可配置查询缓存，冷读或过期查询仍有同步 SDK 边界。已有 Kafka/Nacos RemoteActor 双 JVM ExternalIT，差距限定为正式玩法业务的完整组合。

建议先选 **Player + 一个可持久业务聚合**，或有明确需求时选 Room，完成 local/distributed 两个组合根。框架配方负责 codec、身份、服务绑定、发现/静态路由、受管 IO、lease、恢复和关闭；应用负责账号规则、数据内容和路由策略。无需一次把所有玩法模块都分布式化。

验收：同一份 BO 和业务服务在单 JVM 与独立 JVM 运行；真实持久化、进程重启、旧会话/旧 owner 拒绝、重复请求、未知写结果、慢依赖、排空关闭都能演示。不能以单实例 Adapter 探针或两个内存对象模拟两个进程代替。

### G04 / P1：以真实玩法差异验证可定制性

“有接口”并不等于用户可以替换实际想改的规则。PlayerProfile 固定 uid/version/name/online；SceneEntityState 固定玩家 uid 与二维坐标；Room 固定 WAITING 入房、全员 READY 开局；World 固定排序分片后的 hash 分配。

证据：`PlayerProfile.java:18`、`LocalPlayerService.java:46`；`SceneEntityState.java:16`、`LocalSceneService.java:57`；`LocalRoomService.java:187`、`:192`；`LocalWorldService.java:128`。

建议从真实差异提炼少数窄策略：玩家业务聚合生命周期、RoomRules、ShardPlacementPolicy、场景实体投影/移动校验。线程所有权、状态机、幂等和清理由框架保留；规则交给业务。无需为所有 record 加泛型，也无需构建全能游戏引擎。

验收：全员准备竞技房与允许中途加入/观战的房间共用框架生命周期；玩家加背包/货币后复用保存与离线清理；hash 与亲和性两种分片策略不复制迁移逻辑。只有第二个有实质差异的实现能证明扩展点可用。

### G05 / P1：补齐状态生命周期、持久化及可靠提交后通知的标准选择

当前 data 已有脏对象、并行 flush、正常关闭 flush 和 durable journal，缺的是低成本组合。`DataRuntime.java:69` 默认挂载 PersistenceManager，却没有受管周期 flush；默认快照执行器拒绝 Actor-bound 数据，需要用户自己接回 Actor。DurablePersistenceRuntime 已有周期重放，但配置使用原始 Store 映射，与业务使用的 RepositoryCatalog 入口分离。

奖励样例已保证单聚合内的奖励和领取标记 CAS；但保存后缓存失效或事件失败，重试不会自动补发通知。Room/Ranking/Frame 也有“状态已提交、通知可能失败”的明确语义。把这类情况强行回滚业务状态不正确，重复投递整个命令也可能重复副作用。

建议提供少数显式方案：内存临时状态、Actor 脏对象周期落库、耐久确认、单聚合提交后可靠通知。选定方案后，框架完成快照线程切换、调度、准入、关闭、重放和结果分类。可选 outbox/幂等收据必须与业务存储事务边界一致，不承诺笼统 exactly-once，也不默认重试未知结果写入。

验收：一个玩家业务切片覆盖周期保存、正常退出、写超时、重启恢复、已提交但通知失败；状态与通知结果分开观察；业务不自建通用 flush 定时器和恢复状态机。D04 的仓库替换语义必须先统一。

### G06 / P1（需要实时同步时）：将同步原语组合成完整默认管线

AOI 和状态同步已实现，但 `InMemoryStateSync.java:36` 的 accept 主要校验 seq/baseline/hash 并记录基线，不负责应用业务 payload、ACK、重发或网络恢复。AOI 示例手动生成 snapshot/delta/recovery。帧同步有 tick、Simulation 和 Broadcaster，也还需用户接节拍、传输和补帧。

建议独立可选组合：Scene 状态投影 → AOI → snapshot/delta 编码 → 受预算约束的发送 → ACK/resync/观察者释放。帧同步保留独立语义，明确缺帧、迟到、重连和确定性责任；不要把 rollback、寻路或完整战斗引擎都设为最小默认。

验收：两个真实客户端完成入场、移动、离场；丢一帧 delta、乱序、重复、慢客户端、断线重连之后恢复一致状态。业务只提供投影、规则和必要 codec，不自写基线账本及恢复状态机。

### G07 / P1–P2：提供可选择的一套运营组合

当前有监控 endpoint、日志管线、GM REST 边界与配置热更，但默认 monitor 模块不自动进行系统周期采样或监听；KCP+monitor 已有每秒受管指标采样，scheduler/Netty 也已有日志或可选指标桥。日志默认 System.Logger；GM recipe 使用测试身份且不监听，没有持久幂等/审批/审计的交付组合。

证据：`MonitorRuntimeComponent.java:26`、`MonitorRuntime.java:68,97,128`、`PrometheusHttpEndpoint.java:75`；`LogRuntime.java:31`；`GmRestTransportAdapter.java:68`。System.Logger 可通过部署后端落文件，不应声称完全不能落盘；缺的是框架提供的标准配置与资源管理路径。

建议以选配 operational profile 提供：受管采样、metrics/health/readiness、已选模块指标桥接、标准文件/集中日志出口、drain、GM listener 与一种持久审计/幂等 Adapter。真实身份和业务权限保留 provider，不需内置完整 Web 后台。日志/监控外发均应有容量及失败策略。

验收：只改配置即可启用所需运营能力；依赖故障体现在 readiness；停机先排空、保存，再释放客户端与执行器；不启用运营组合的最小程序不引入这些资源。

### G08 / P2：让高级定制能力贯穿到 runtime 入口

需要补齐的是少数可证实的不对称边界：

| 边界 | 当前情况 | 改进方向 |
| --- | --- | --- |
| 缓存 | runtime 绑定单个 `CacheService<Object,Object>`，默认固定 CachePolicy；自动加载、版本失效等在具体类 | 具名、类型化 CacheDefinition 与窄能力接口；策略可配置，不强迫业务 downcast |
| 监控 | MonitorRuntime 参数及返回值绑定 InMemoryMetricRegistry | 让 MetricRegistry 或必要窄能力贯穿 facade，保留默认实现 |
| Adapter 恢复 | RuntimeAdapterIo 固定 conservative recovery；高级 SPI 没有贯穿默认真实 provider | 可注入恢复策略/重建操作，复用默认安全生命周期；已有健康恢复无需重造 |
| KCP codec | KcpServer 可注入 ProtocolFrameCodec，KcpRuntime 固定 ZeroBinaryFrameCodec | 高级 options/重载开放 codec，保持简单默认 |
| 受管调度 | ManagedScheduler 契约在 core，正式实现与工厂仍在全量 Starter | 将可独立消费的调度实现/集成接入最小运行时；不要求仅为定时任务引入全部游戏组合 |

源码：`CacheRuntime.java:19,36`、`CacheService.java:13`；`MonitorRuntime.java:19,51,128`；`RuntimeAdapterIo.java:61`；`KcpRuntime.java:85`；`ZeroManagedSchedulerFactory.java:64`、`zero-server-starter/pom.xml`。这些问题不能通过机械拆文件解决；应以“替换一个真实实现是否仍需复制装配代码”检验设计。

### G09 / P2：按目标类型补传输与数据能力，不扩大最小内核

- **浏览器/H5**：WebSocket 当前只有 enum，无实际 server adapter。优先交付可选 WebSocket 与生成 TypeScript 客户端的真实业务闭环。
- **轻量多进程**：local 与 Kafka 之间缺免 broker 的常规 RPC Adapter。若轻量中心/逻辑拆分是主要需求，可评估一个 TCP/HTTP transport，复用同一安全/超时/codec 契约。
- **KCP 集群**：Redis ownership 原子脚本和本地迁移测试都有，但缺真实 Redis + 两个 JVM + 客户端迁移的受管配方与故障矩阵。
- **平台/后台查询**：目前 Repository 偏对象 KV；PostgreSQL 是 bytea envelope，不能替代账号唯一索引、关系查询、筛选排序。另加 Query/Index/Cursor 能力和自定义仓库配方，保持最小 CRUD 简洁。
- **大集合遍历**：`RedisDriverEnvelopeStore.java:217,248` 每页扫描排序整个索引，累计 SSCAN 返回项预算为 100000，可能含重复项，超预算拒绝；该数字不是精确的最大唯一实体数。应为需要该规模的场景提供有界分页路径或清晰的后端能力选择提示。
- **配置运营**：本地 CSV 热更已可用；优先补多表配置包、跨表校验、版本发布、节点收敛与回滚。CGLIB/ClassLoader 热补丁按真实需求后置。

具体模块不必全都默认启用。通用性来自可组合契约与经过验收的典型组合，并不要求所有数据库支持完全相同的最宽查询接口、所有游戏都装同一套引擎。

### G10 / P1–P2：把使用说明变成持续可验证的产品契约

本轮 Stage 0 quick 通过，却仍发现公开外部示例接线回归。`ZeroStage0Acceptance.addExampleChecks` 的 full 列表仍集中在较早示例；新 `module-recipes`、`business-slice`、`multi-server-player` 没有进入其列表或根 Reactor，CI 中也没有直接对应入口。

建议维护一个场景清单：声明命令、实际使用模块、是否需要外部服务、期望行为和证据层级；从清单派生文档、示例验收和能力矩阵。验收应运行公开 Main/组合根，而非只运行另写的相似 fixture。默认测试覆盖无外部接线；外部模式单列并明确 skip/blocked，不得由本地绿灯替代。

同步收敛文档的用户视角：首屏按“新增请求、增加业务数据、换数据库、加房间规则、拆进程、上线观测”导航。用户第一次开发不应先阅读 62 个模块介绍；模块图保留给扩展与排障。

验收：所有公开命令有所属门禁；缺安全 provider/执行域/codec 能在诊断或接线测试阶段暴露；能力矩阵显示代码日期及验证层级，不再把历史结果当当前结果；“首次业务修改”可以沿单一指南完成。

## 5. 逐模块矩阵

下表逐项覆盖默认模块及 benchmark。状态描述的是实现与目标之间的关系，不是生产认证。模块源码/契约与关键测试均做了分组检查，未声称逐行审读所有测试或重新执行所有外部测试。

### 5.1 构建、核心与游戏（18 个）

| 模块 | 已有基础 | 下一步最有价值的内容 |
| --- | --- | --- |
| zero-parent | Java 21、Maven 插件、默认测试与 quality profile | 将公开消费者组合纳入验收；本轮无新依赖规范缺陷 |
| zero-bom | 框架制品统一版本管理 | 保持所有独立消费者版本对齐；正式制品发布另行确认 |
| zero-core | 配置、生命周期、错误码、Provider、调度契约，无具体中间件 | 保持职责克制；受管调度独立消费见 G08 |
| zero-event | 优先级、拦截、异步完成、失败聚合、死信及显式重试 | 持久死信/通知重放组合、业务幂等策略；G05 |
| zero-actor | Local/Executor、lane 串行、预算、挂起、超时隔离 | 与远程 owner/恢复的外围集成；G03 |
| zero-game | 请求上下文及本地/远程 Actor 网关 | 将玩法服务接入可替换网关、序列化命令与路由；G03 |
| zero-player | 登录、UID/session 绑定、加载单飞、受管 IO、logout 清理 | 自定义在线聚合、保存/离线生命周期及 owner；G03–G05 |
| zero-scene | 场景 lane、进入/移动/离开、快照 | 实体投影、移动规则、AOI/网络组合；G04、G06 |
| zero-room | 生命周期、成员、重连窗口、幂等结算、有界历史 | 规则策略、持久恢复、可靠结算通知；G03–G05 |
| zero-world | world/shard/entity、本地迁移、序号归档 | placement 策略、业务 ownership、历史 ID 内存边界；D05、G03 |
| zero-npc | zone lane、BehaviorAdapter、生命周期、预算与隔离 | 先修 tick 公平性/预算和异常诊断；D03、D06；再补自定义状态组合 |
| zero-ranking | 分数合并、稳定排名、Top、赛季、幂等/归档 | 历史索引回收、持久/跨服实现、可靠奖励通知；D05、G05 |
| zero-aoi | AoiIndex、二维网格、可见性增量、观察者回收 | 网络同步组合；真实第二空间/兴趣模型验证扩展点；G06 |
| zero-state-sync | seq/version/hash 基线校验、不可变 payload、容量 | 发送/ACK/投影/恢复与业务应用管线；G06 |
| zero-frame-sync | 输入窗口、迟到/缺失策略、固定帧推进、Simulation/Broadcast | 受管节拍、网络/重连补帧和提交后交付；G05、G06 |
| zero-logic | LogicSession、类型化属性、会话目录、本地流程 | 明确示例与正式会话入口职责，统一登录/关闭/场景离开编排 |
| zero-ownership | lease/epoch、CAS/幂等、迁移恢复、容量端口 | 正式玩法接入、IO 桥接、统一收据归档及资源退役；G03 |
| zero-ownership-postgresql | 同事务 fencing/CAS/replay、持久迁移与路由 | PG 收据生命周期；将构造器自动 DDL 与运行账户验证分开；业务双 JVM 样板 |

### 5.2 协议、网络、RPC、安全与发现（12 个）

| 模块 | 已有基础 | 下一步最有价值的内容 |
| --- | --- | --- |
| zero-protocol | 帧/codec SPI、预算、缓冲、错误帧、协议注册 | 业务响应与跨端真实调用闭环；无需重做 codec 框架 |
| zero-codegen | DSL、多语言 DTO/codec、BO/dispatcher、脚手架/catalog/升级 | 正式模块模板、异步结果边界、公开使用路径回归；G01、G02、G10 |
| zero-net | Netty TCP/UDP/HTTP、资源归属、生命周期、背压 | 可选 WebSocket；更简洁的显式 listener/安全组合；G07、G09 |
| zero-net-kcp | 预设、可靠收发、保护/FEC、路径恢复、owner/迁移 | 真实跨 JVM/弱网客户端组合与场景容量证据；G09 |
| zero-net-kcp-redis | Redis Lua ownership 状态机、有界 IO 准入 | 受管 Redis/KCP 配方、真实 socket 故障矩阵；G09 |
| zero-rpc-common | 方法注解/ID、RpcResult、request/response/oneway、幂等声明 | 可选执行策略；fanout 显式目标/预算/部分结果，不误当自动广播 |
| zero-rpc | typed client/binder、codec、local、deadline/取消、Actor 远程桥 | codec/client/service 受管 facade、发现路由和上下文；G02、G03 |
| zero-rpc-kafka | 真实 producer/consumer、时间轮、容量、确认与安全 | 先修示例身份断链；独占 reply topic 的 ACL/provision/清理配方；D02 |
| zero-rpc-discovery | 发现实例到 RPC metadata/resolver | typed client 接入、异步/订阅快照路由；G03 |
| zero-discovery | 中立发现契约和内存实现 | 服务绑定/注册/readiness/drain/注销组合，不强制所有服务走发现 |
| zero-discovery-nacos | 真实 SDK、缓存代际、订阅、健康、关闭保护 | 同步 SDK 的 IO 域收口、集群恢复与规模证据；G03 |
| zero-security | 身份/重放/TLS 契约、HMAC、上下文、可信代理 | 可替换安全配方与异步传播、缺配置早期诊断；G02、G03 |

### 5.3 数据与运营（10 个）

| 模块 | 已有基础 | 下一步最有价值的内容 |
| --- | --- | --- |
| zero-data | RepositoryFactory/Catalog、映射/CAS、批量/页读、flush、durable | 统一便利内存契约、业务保存方案和可靠通知；D04、G05 |
| zero-data-mongo | 真实驱动、集合隔离、版本写、批量/分页 | 可选查询/索引配方、游标与规模证据；G09 |
| zero-data-redis | 原子数据脚本、snapshot/index/journal、L2 cache | 有界大集合分页、恢复与部署适用范围；G09 |
| zero-data-postgresql | 参数化 SQL、CAS、DataSource、envelope 存储 | 可选关系查询/唯一约束、schema 部署与定制仓库；G09 |
| zero-cache | L1/L2、single-flight、负缓存、TTL jitter、版本/迟到写保护 | 具名类型化缓存、策略配置与窄高级接口；G08 |
| zero-log | 结构化管线、字段校验、脱敏/HMAC、Sink 隔离 | 可选标准文件/集中输出、队列预算、drain；G07 |
| zero-monitor | registry、系统采样、Prometheus endpoint、Grafana、告警评估 | 受管采样/监听/指标桥接；registry 可替换；G07、G08 |
| zero-gm | DSL、授权/IP、审批、dry-run、幂等与审计状态 | 一种可部署持久 provider 及业务结果恢复组合；G07 |
| zero-gm-rest | 有界 JSON/HTTP 适配、可信身份端口、稳定响应 | 受管真实监听与生产身份配方；G07 |
| zero-hot-update | 本地 CSV、不可变快照、校验/授权/观察/Watcher | 多表配置包、集群版本收敛和回滚；代码热补丁后置；G09 |

### 5.4 Runtime、Starter 与性能（22 个）

| 模块 | 已有基础 | 下一步最有价值的内容 |
| --- | --- | --- |
| zero-runtime | 显式图、typed config、延迟工厂、替换、事务资源生命周期、健康 | 保持中立；用高层场景组合隐藏普通用户不需要的装配细节 |
| zero-runtime-bootstrap | 最小配置/执行器、受管 IO、延迟资源 | 运行档位/执行域用法进一步收口，接入可独立消费的 ManagedScheduler |
| zero-runtime-event | EventBus/DeadLetterSink 显式绑定 | 可选持久通知策略与业务执行域组合；G05 |
| zero-runtime-actor | 受管 scheduler、显式预算、关闭接线 | 场景化线程/预算默认值及跨部署契约；G03 |
| zero-runtime-protocol | 中立协议 registry | 与生成 codec、handler、响应链路整合；G02 |
| zero-runtime-rpc | transport/handler registry/resolver 能力键 | codec/client/service binding、身份和路由的受管入口；G03 |
| zero-runtime-data | local source、role/source catalog、PersistenceManager、durable | Actor 快照/周期 flush 与统一持久化策略选择；G05 |
| zero-runtime-cache | 单个 CacheService 默认绑定 | 多种业务缓存的类型与独立策略；G08 |
| zero-runtime-log | 安全管线、可替换 terminal sink/资源 | 标准输出配置与生命周期配方；G07 |
| zero-runtime-monitor | MonitorRuntime 默认绑定 | 持续采样、endpoint、底层指标按配置接入；G07 |
| zero-runtime-discovery | local discovery/resolver | typed client 与服务注册关闭组合；G03 |
| zero-runtime-production | 选配、脱敏诊断、预算、回滚、运行期准入/恢复 | 高级恢复注入与业务 drain/运营组合；G07、G08 |
| zero-runtime-kafka | 真实客户端、reply 隔离、健康恢复、日志/IO 依赖 | 缺身份早期诊断、topic 运维计划；D02、G03 |
| zero-runtime-mongo | 真实 client/source、启动/运行期探测及资源归属 | 高级查询配方与恢复策略注入；G08、G09 |
| zero-runtime-redis | 共享 data/cache 资源、共同健康、写回维护 | cache policy/高级能力可配置，KCP 资源桥接；G08、G09 |
| zero-runtime-postgresql | Hikari、source、探测与预算 | 平台查询/池配置配方及恢复策略注入；G08、G09 |
| zero-runtime-nacos | 真实发现/resolver、运行期观测 | 同步 IO 边界、注册/readiness/drain/注销组合；G03 |
| zero-runtime-net | Netty IO、生产网络 lifecycle/安全/observer | listener/handler/safety 的统一可选组合，减少复制接线 |
| zero-runtime-kcp | 多监听、typed schema、preset、可选观测、生命周期 | 帧 codec 定制、Redis 所有权资源归属；G08、G09 |
| zero-server-starter | 完整本地组合、TCP 生命周期、受管调度/热配置 | 统一业务入口、可选能力独立消费；G01、G02、G08 |
| zero-server-starter-production | 全量真实 Adapter 便利组合、可定制网络/日志 | 业务服务与运营默认配方；名称不代表业务生产验收已完成 |
| zero-benchmarks | 显式 JMH 叶子、若干机制的历史性能证据 | 围绕真实组合建立可重复场景容量/分配/p99/拒绝率基线；本轮未重测 |

## 6. 推荐实施顺序与完成定义

### 阶段 A：先恢复所有承诺入口的可信度

处理 D01–D03，并安排 D04–D06；同步能力矩阵，建立公开示例清单和默认接线门禁。新增示例纳入持续验收。此阶段的产物是可复现的修复和回归，不需要大重构。

完成定义：公开本地入口全通过；外部模式缺配置/安全材料能早期报告；配置齐全时至少进入正确的启动阶段；NPC 多轮公平性和仓库替换契约有回归证据。

### 阶段 B：统一“写一段业务”的默认路径

选择带自定义数据的玩家业务，统一异步 BO、请求上下文、类型化响应和保存方案；让 local 模板与业务切片直接消费正式模块。再选择一种房间规则差异，验证扩展点，逐步迁移其余模板。

完成定义：业务开发者新增请求或字段只修改协议、业务实现、领域数据/规则与对应测试；不重复写 dispatcher 异步桥、线程切换、保存定时器或通用关闭代码。生成代码量不是目标，用户必须理解/维护的通用代码量才是指标。

### 阶段 C：在同一业务上完成真实多进程与运营组合

复用阶段 B 的业务，接 ownership、RPC/发现、真实 Repository、安全和最小观测/GM。明确本地与分布式承诺差异；把配置、服务注册、drain、checkpoint 和恢复放在可选组合层。

完成定义：两个真实 JVM、一套实际数据源，完成启动、业务、故障、接管、旧写拒绝、重启恢复和退出；默认 local 不因此依赖数据库或 broker。高级能力可关闭，也可替换一个 provider 后保持同一契约测试。

### 阶段 D：按真实需求扩展场景能力

H5 优先 WebSocket；实时场景优先完整同步管线；原生弱网对战优先 KCP 集群；平台服务优先查询/索引；持续运营优先集群配置包。免 broker RPC、通用幂等/可靠通知、持久 GM、日志出口按前面纵切片暴露的需求推进。

不要把完整账号系统、所有玩法、支付、任务树、战斗/寻路、全网热补丁同时塞进 core。可复用背包/资产、奖励、邮件、冷却/次数限制、匹配等应在可选业务组件层按实际需求交付，先明确事务、幂等和扩展规则，再扩大目录。

### 建议采用的产品验收指标

这些是后续验收建议，尚无本轮用户实验数据，不宣称已经达到：

- 从干净检出到首次业务请求成功的步骤、必要配置项及耗时。
- 新增一条异步业务请求时，需要接触的文件、基础设施概念和重复代码量。
- 换数据源、缓存策略或传输时，是否修改业务服务；为哪些明确语义差异必须修改。
- 两个实质不同的玩法能否复用同一状态生命周期，而不是复制 Local 实现。
- 单机到多进程时，需要新增的通用编排代码，以及故障后需人工干预的步骤。
- 持久化、重放、排空和观测能否只通过选定组合完成；未选能力是否零外部依赖。

## 7. 本轮验证与局限

环境：Windows、JDK 21.0.4（`D:/env/jdk21`）、Maven 3.9.8（`D:/env/apache-maven-3.9.8`）。默认 PATH 的 Java 为 17，本轮仅在子进程中覆盖环境，没有修改全局设置。

| 检查 | 本轮结果 | 证据 |
| --- | --- | --- |
| Stage 0 quick | 6/6 通过，约 129 秒；含 Doctor、架构/边界、全仓默认测试、安装、关键词生成与业务运行 | `target/product-goal-review-20261012/stage0-quick.log` 及 `stage0/logs/` |
| 全仓默认测试统计 | 仅统计此次更新的 348 个 Surefire XML：1354 tests，0 failures，0 errors，1 skipped | 各默认模块 `target/surefire-reports/`；旧 XML 不纳入本轮统计 |
| module-recipes | 七个子工程共 8 tests 通过 | `target/product-goal-review-20261012/example-module-recipes.log` |
| business-slice | 7 tests 通过 | `target/product-goal-review-20261012/example-business-slice.log` |
| multi-server-player | 9 tests 通过，证明本地示例契约，不证明独立进程运行 | `target/product-goal-review-20261012/example-multi-server-player.log` |
| NPC fairness probe | 成功复现 D03，10 轮中第二个 NPC 决策次数为 0 | `target/product-goal-review-20261012/NpcFairnessProbe.java`、`npc-fairness.log` |
| Repository Redis 公开入口 | exit 1，在连接前复现 D01 的执行域配置拒绝 | `target/product-goal-review-20261012/repository-redis-assembly.log` |
| 源码规模抽样 | 已跟踪框架 main Java 最大文件为 LocalManagedScheduler，1422 行，未发现超过 1500 行的框架主 Java 文件 | 静态文件行数统计；未声称完成每个方法的长度审计 |

未执行：本轮完整 quality/full Stage 0、全部生成组合、跨语言互操作、真实数据库/Kafka/Nacos、跨 JVM 游戏流程、跨平台 CI、容量/长稳/公网安全、发布和灾备。历史通过记录与当前已有测试源码只用于理解能力，不提升为本轮通过结论。唯一默认 skipped 为 `RedisKcpSessionStoreTest`，不计作外部测试成功。默认测试逐套件统计另存 `target/product-goal-review-20261012/test-summary.json`。

本次只新增审查报告及索引、任务材料；未改业务代码和既有审计结论。开始时工作区已有 `docs/audits/2026-10-06-full-code-audit.zh-CN.md` 修改，保持原状。无行为、协议、公共 API 或存储格式变更，因此不产生本轮迁移要求；后续修复依仓库规则单独记录 0.x 迁移与验证。
