# 灵活性、扩展性与定制能力评估

> 2026-09-30 后续增量：本轮按用户确认先交付易用性基础。标准配置现在通过 `ConfigLookup` 保留来源并处理应用默认值优先级，实际计划携带同次解析的脱敏配置；生成项目的诊断模型已统一，显式 JSON catalog 贯通 CLI/生成/升级。见[配置与目录指南](guides/configuration-and-catalog.zh-CN.md)及[迁移说明](migrations/20260930-flexibility-foundation.zh-CN.md)。下文保留原评估基线与路线排序；ownership 业务纵切片、受管 IO、真实集群故障矩阵仍未由本轮实现，不据此提升分布式或生产就绪评分。

评估日期：2026-09-30。评估对象是当前 checkout，根 POM 版本为 0.1.2-SNAPSHOT；本文不是 0.1.1 发布验收。结论来自当前源码、模块 POM、公开文档、迁移记录和可复现测试。productionReady=false 仍是全项目的边界条件。

本文把四件事分开判断：能否选择能力、能否替换实现、能否跨场景迁移、能否用测试证明语义。存在 SPI、配置开关或独立 Maven 模块，只能证明存在扩展入口，不能自动证明有第二个可用实现，更不能证明跨进程状态已经具备所有权、恢复和容量语义。

## 结论

当前项目的**基础设施装配灵活性较好，业务状态的分布式扩展性仍有限**。

- zero-runtime、独立 zero-runtime 集成模块、显式 install/register/override/replace、Repository 多实现、typed 配置诊断和脚手架组件元数据，已经形成了可以按需裁剪的装配内核。
- 最小运行时、单机 Starter、单进程外部依赖和生产组合存在清晰入口，但它们的依赖宽度、默认策略和验证强度不同，不能把“可生成”当成“已接入生产能力”。
- 2026-09-30 新增的 zero-ownership 和 zero-ownership-postgresql 把 lease、epoch fencing、状态 CAS、requestId 幂等、迁移 checkpoint 和容量预算提升为通用最小切片；它们尚未接入 Player、Scene、Room、World、Ranking 的业务闭环。
- Player、Scene、Room、World、Ranking 和帧同步的关键状态仍主要由单 JVM 对象和本地 Actor lane 持有。替换数据库、RPC 或 Actor Scheduler，不会自动得到跨节点 owner、迁移、重放和故障恢复。
- Netty 是网络实现的明确技术基线；Kafka 是唯一完整的生产 RPC transport；Nacos 是唯一完整的生产 discovery adapter。它们属于需要记录边界的单一强实现，而不是应当为了“形式上的可替换”立即拆分的抽象。

综合评分为 **3/5**：基础设施定制能力约 **4/5**，单机原型约 **4/5**，多实现成熟度约 **3/5**，分布式业务扩展约 **2/5**，低心智负担约 **3/5**。下一阶段最有价值的工作是把 ownership 最小切片接入一条真实业务纵切片，并补齐受管远程 IO、配置一致性和双进程故障证据。

## 评分口径与维度结果

5 分表示同一契约有第二实现、场景装配和故障测试；4 分表示已有显式装配、替换入口和 focused tests，但生产或跨进程证据不完整；3 分表示已有 SPI 或多种路径，但第二实现、组合约束或使用路径仍有限；2 分表示主要是本地/原型实现，扩展点不能覆盖关键语义；1 分表示调用方直接依赖单一具体实现。

| 维度 | 评分 | 现状判断 | 主要限制 |
| --- | ---: | --- | --- |
| 高度可选 | 4/5 | RuntimeComposition 可显式安装、注册、覆盖和替换组件；能力模型与实际 Provider 分离 | 标准 Provider 目录仍由框架维护；未接入 classpath 自动发现 |
| 按需定制 | 4/5 | zero-runtime-bootstrap、各 zero-runtime 集成模块和 Production Assembly 可以按能力选择 | 全量 Starter 仍带宽依赖；应用需要理解 profile、Provider、资源所有权和关闭顺序 |
| 多实现替换 | 3/5 | Repository 有内存、MongoDB、Redis、PostgreSQL；RPC、发现、网络有中立端口 | 多数外部能力只有一个完整实现；契约测试和语义对照不足 |
| 单机到分布式配置 | 3/5 | local、external-test、production profile 和独立 Adapter 已存在；ownership 有通用最小切片 | 分布式业务路由、状态恢复和消息编排尚未接入业务模块 |
| 最小原型 | 4/5 | RuntimeBasics 可只安装配置和执行器，不引入 Netty、Broker 或数据库 | 业务模板默认仍会带协议、Actor、日志和监控，和纯 runtime 最小路径容易混淆 |
| 低心智负担 | 3/5 | 脚手架现在用 ScaffoldComponentDefinition 统一 Provider、坐标、安装表达式和配置样例 | typed config、旧 ZeroConfig、Production 专用解析路径仍需同时理解；外部 Adapter 的同步语义不透明 |
| 高度扩展化 | 3/5 | 应用可提供自定义 Provider、Repository、Cache、Actor、网络策略和 payload codec | 游戏状态端口没有统一 owner/恢复实现；自定义实现要自行补齐容量、幂等和生命周期 |
| 可验证性与生产信心 | 2/5 | 本地 focused tests、ownership 双 JVM PostgreSQL 单实例证据已存在 | Kafka/Nacos/数据库复制集、长稳、容量、跨机房和生产安全链仍未证明 |

## 从单机到分布式的场景评估

| 场景 | 当前可用路径 | 定制边界 | 评价与缺口 |
| --- | --- | --- | --- |
| 纯最小原型 | zero-runtime-bootstrap + zero-runtime + zero-core；RuntimeBasics.builder() | 只安装配置、执行器及所需本地能力 | 依赖图最小，适合协议/事件/Actor 试验；不要把 LocalRuntimePresets.minimal() 误称为最小依赖，它仍带 Starter 侧日志和监控 |
| 单机业务 | 本地 Starter、业务模板、Player/Scene/Room/World 等 local 实现 | 自定义 Repository、Cache、Actor、网络策略和日志 sink | 便捷性最好，但核心状态仍在当前 JVM；默认实现更接近原型而非可恢复服务 |
| 单进程 + 外部组件 | [ProductionAssembly](../zero-runtime-production/src/main/java/group/zn/zero/runtime/production/ProductionAssembly.java#L68) 显式选择 Kafka、Nacos、MongoDB、Redis、PostgreSQL 或网络生命周期 Provider | 组件可独立添加，应用可覆盖 Provider；外部失败不隐式回退到本地实现 | 适合受控集成测试；驱动健康、超时、恢复、容量和同步 IO 执行域需由应用验证 |
| 双进程最小分布式 | zero-ownership + 内存实现用于契约测试，zero-ownership-postgresql 提供持久化 lease/state/migration/route | owner generation、process incarnation、epoch、CAS、requestId 幂等、checkpoint 元数据和有界容量可独立选用 | 基础设施达到 minimum-slice；未自动替换 Player/Room/World 的本地 map，也未绑定 Kafka/Nacos 通知 |
| 多进程业务集群 | Kafka RPC + Nacos discovery + 外部 Repository/Cache + ownership 端口 | 可组合 transport、发现、数据和 owner 路由 | 需要业务自己定义 owner 交接、重放、断线恢复、消息顺序和降级；真实 broker/集群故障证据不足 |
| 生产运营 | Production profile、启动健康、预算、逆序关闭和安全诊断 | 可以按需选择外部 Adapter 和安全链 | 不能从模块存在推导生产就绪；公网安全、复制集切换、容量和长稳仍是应用责任 |

最小装配入口见 [RuntimeBasics](../zero-runtime-bootstrap/src/main/java/group/zn/zero/runtime/bootstrap/RuntimeBasics.java#L40) 和[按需装配指南](guides/modular-composition-guide.zh-CN.md)；组合内核见 [RuntimeComposition](../zero-runtime/src/main/java/group/zn/zero/runtime/assembly/RuntimeComposition.java#L21) 和 [StandardRuntimeCapabilityModel](../zero-runtime/src/main/java/group/zn/zero/runtime/capability/StandardRuntimeCapabilityModel.java#L12)。

## 已有灵活性与可复用设计

| 领域 | 当前能力 | 可复用价值 | 仍需确认的语义 |
| --- | --- | --- | --- |
| 运行时装配 | 显式模块、能力 Provider、依赖闭包、资源账本、启动/关闭和回滚 | 适合构建 local、external-test、production 三类组合；核心不依赖具体中间件 | Provider 冲突、生命周期和每组件预算应继续用契约测试固定 |
| 配置 | RuntimeConfigResolver 聚合 schema；RuntimeConfigDiagnostics 输出来源、类型、状态、reloadability 和遮罩值 | 排障不必读取敏感原始值；缺省、optional 和冲突可以被发现 | typed schema、旧 ZeroConfig 和 Production 解析路径的优先级仍需统一说明 |
| 脚手架 | [ScaffoldComponents](../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ScaffoldComponents.java#L19) 使用 [ScaffoldComponentDefinition](../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ScaffoldComponentDefinition.java#L20) 统一 Provider、Maven 坐标、装配表达式和配置样例 | 新增测试 Provider 时可通过显式目录复用生成、配置和诊断逻辑；未知/重复目录项会在写盘前失败 | 标准目录仍是固定清单；扩展方需要显式构造目录，不能仅放入 classpath 就出现 CLI 选项 |
| Repository / Cache | CrudRepository、RepositoryFactory、命名来源与角色绑定；内存、MongoDB、Redis、PostgreSQL 和 L1/L2 Cache 路径 | 是当前最接近“同一业务接口、多实现”的部分 | CAS、关闭、批量、事务、同步阻塞和网络分区语义必须逐 Adapter 验证 |
| ownership | OwnershipStore、FencedStateStore、MigrationStore、OwnershipRouter、[FailoverCoordinator](../zero-ownership/src/main/java/group/zn/zero/ownership/FailoverCoordinator.java#L13)、CapacityBudget；内存和 PostgreSQL 两条路径，PostgreSQL 状态提交见 [PostgresqlFencedStateStore](../zero-ownership-postgresql/src/main/java/group/zn/zero/ownership/postgresql/PostgresqlFencedStateStore.java#L20) | 把 owner、epoch、状态版本、迁移记录和容量从具体游戏类型中抽出，避免把分布式逻辑塞进 Local 服务 | schema migration、数据库复制集、消息通知和业务 payload codec 仍由应用负责 |
| RPC / Discovery | 中立 RpcTransport、RpcHandlerRegistry、ServiceDiscovery；本地和 Kafka/Nacos 路径 | 调用方可以在 local 与外部组合之间切换 | Kafka Provider 同时提供 transport/handler registry；Nacos 是唯一完整外部发现实现，组合约束需显式保留 |
| 网络 | zero-net 暴露连接、生命周期、限流、安全和 observer 端口；KCP 另有独立 Adapter | 网络策略与 Netty 资源拥有权已有分层，可按需添加 TCP/UDP/HTTP/KCP | TCP/UDP/HTTP 具体实现仍是 Netty；WebSocket 和第二 IO 内核尚未提供 |
| 日志 / 监控 | LogSink、AlertSink、MetricRegistry、Prometheus 文本端点与本地 sink 可替换 | 低基数和脱敏规则可以复用到不同输出 | durable file/Kafka sink、远程写入、认证/TLS 和生产背压没有统一实现 |

## 强耦合模块与功能

下表区分“有意的技术基线”和“阻碍替换的业务耦合”。前者应先记录边界和验证要求，后者优先通过外围适配和纵切片解除，避免把所有本地实现机械抽成空泛 SPI。

| 优先级 | 耦合点与证据 | 影响 | 建议与验收条件 |
| --- | --- | --- | --- |
| P0 | [LocalPlayerService](../zero-player/src/main/java/group/zn/zero/player/LocalPlayerService.java#L35) 直接持有 session/player map，并把请求绑定到本地 Actor lane；[LocalSceneService](../zero-scene/src/main/java/group/zn/zero/scene/LocalSceneService.java#L25) 同样把 scene 状态和 handler 放在一个对象内 | Repository/Cache 只能替换存取，不能替换在线会话 owner；节点退出、重复请求和跨服迁移没有通用语义 | 先在 Player 或 Room 选择一条纵切片，接入 owner route、lease/epoch、远程代理和 lane 写入；双 JVM 验收旧 owner fencing、迟到消息、断线恢复、重试幂等和节点退出 |
| P0 | [LocalWorldService](../zero-world/src/main/java/group/zn/zero/world/LocalWorldService.java#L23)、[LocalRoomService](../zero-room/src/main/java/group/zn/zero/room/LocalRoomService.java#L21)、[LocalRankingService](../zero-ranking/src/main/java/group/zn/zero/ranking/LocalRankingService.java#L17) 以本地表/锁/有界历史承载全部关键状态 | 外部数据库或 Redis 并不会自动产生全局 owner、事件重放、赛季结算一致性或恢复点 | 保持 local 实现作为原型；为一个真实业务定义 snapshot/codec、事件边界、owner 交接和重放测试，禁止把所有模块同时改成“分布式版” |
| P0 | [NettyTransportFactory](../zero-net/src/main/java/group/zn/zero/net/netty/NettyTransportFactory.java#L16) 及 zero-net/netty 下的服务器、buffer、连接和 IO 资源形成单一 TCP/UDP/HTTP 具体实现 | 更换 IO 内核需要重写资源、生命周期、连接属性、frame bridge、出站预算和测试；当前不存在第二个等价 transport | Netty 作为已确认技术基线保留；先把 transport/lifecycle 契约测试、容量和安全链做完整。只有出现真实替换需求时再引入第二 IO Adapter |
| P1 | [RpcRuntime](../zero-runtime-rpc/src/main/java/group/zn/zero/runtime/rpc/RpcRuntime.java#L17) 的本地 Provider 同时绑定 RpcTransport 与 RpcHandlerRegistry；Kafka Provider 也成对提供二者 | 不能只替换 transport 或 registry 而假定语义仍一致；第三方 transport 要同时满足 request/response、oneway、broadcast、超时和关闭 | 保留成对 binding，加入 local/Kafka/第三方的共同契约测试；错误组合在 plan 阶段失败 |
| P1 | Kafka 是唯一完整生产 RPC Adapter，Nacos 是唯一完整生产 Discovery Adapter | Broker/注册中心的故障模型、部署模型和运维工具会渗入多进程方案；SPI 存在但替换成本仍高 | 不立即增加空实现；记录 Kafka/Nacos 的生产边界，优先补 broker/集群故障证据和一个真实替代需求驱动的 Adapter |
| P1 | Repository API 返回 CompletionStage，但工厂创建和 envelope store 调用仍可能同步阻塞，详见[Repository 指南](guides/repository-composition-guide.zh-CN.md#L52) | 异步返回类型会让调用方误以为 Actor 热路径安全；数据库延迟可能阻塞 lane | 为真实 Adapter 标注同步/异步执行语义，提供有界受管远程 IO 域、deadline、取消、队列和关闭测试；禁止用无界线程池包裹 |
| P1 | 配置存在 typed schema、ZeroConfig 合并和 Production 专用解析路径；[RuntimeConfigResolver](../zero-runtime/src/main/java/group/zn/zero/runtime/config/RuntimeConfigResolver.java#L23) 与 bootstrap 的读取边界不同 | 同名键的来源优先级和有效值可能需要跨多条路径排查；诊断已改善可见性，但未消除路径差异 | 建立唯一“有效配置 + 来源 + 脱敏值”消费契约，让 Provider 看到的值与诊断一致，并测试文件/系统属性/环境变量冲突 |
| P2 | 标准脚手架目录由 ScaffoldComponents 内置定义驱动，扩展方需要显式传入定义 | 便捷性和安全 allowlist 之间存在取舍；第三方 Provider 不能自动出现在 CLI | 保持显式目录和冲突检测；后续可提供版本化 catalog/插件注册，但必须保留未知 ID 写盘前失败 |
| P2 | 日志、监控当前以内存/本地输出和 Prometheus 文本端点为主 | 生产输出、远程写、告警通道和背压由应用自行接线，跨环境迁移成本较高 | 先冻结字段、脱敏、低基数和 observer 背压契约；出现真实输出需求后增加独立 sink Adapter |

## 依赖单一强实现的排查

| 能力 | 中立端口 | 当前实现 | 单一实现风险 | 当前判断 |
| --- | --- | --- | --- | --- |
| 网络 IO | NetworkTransport、ServerFactory、lifecycle/observer | Netty TCP/UDP/HTTP；KCP 为独立协议实现 | IO 内核、buffer、事件循环和关闭模型无法直接替换 | 有意技术基线；风险需用容量/安全/长稳证据覆盖 |
| RPC transport | RpcTransport、RpcHandlerRegistry | InMemory + Kafka | 生产 broker 语义、重平衡、积压和跨机房故障集中在 Kafka | 外部实现单一；保留 SPI，补契约和故障矩阵 |
| 服务发现 | ServiceDiscovery、RPC resolver | InMemory + Nacos | 生产注册、健康、订阅和鉴权绑定 Nacos | 外部实现单一；不能把 local 替代当成生产第二实现 |
| Actor 调度 | ActorScheduler、lane/route 端口 | Local/Executor；没有通用集群 scheduler | 远程 actor、再平衡、持久邮箱和容量语义缺失 | 单机实现充分，分布式能力应由 ownership + RPC 外围实现 |
| 数据 | Repository、RepositorySource、DataService | InMemory、MongoDB、Redis、PostgreSQL | 不同 Adapter 的事务、CAS、阻塞和恢复语义可能不等价 | 当前最灵活；需要跨实现契约测试 |
| Cache | CacheService、CacheStore | 本地 L1/L2、Redis 分布式路径 | 集群失效、热点和网络分区策略不是统一保证 | 可替换但未证明生产容量 |
| Ownership | OwnershipStore、FencedStateStore、MigrationStore、OwnershipRouter | 内存 + PostgreSQL | 持久实现仍单一；数据库复制集和 schema migration 未覆盖 | minimum-slice；足以做纵切片，不能宣称生产高可用 |
| 日志/监控 | LogSink、AlertSink、MetricRegistry | InMemory/SystemLogger、Prometheus 文本 | durable sink、远程告警和审计持久化需应用选择 | 端口清楚，实现面偏窄 |
| 游戏业务状态 | PlayerService、SceneService、局部 Room/World/Ranking API | 主要由 Local 前缀类实现 | 本地 map、锁和 handler 同时承载状态与执行；替换后仍没有 owner/恢复语义 | 目前最大的扩展缺口，优先做一条真实跨进程链路 |

## 低心智负担与便捷性的具体判断

当前使用体验是“手工装配能力强，默认路径简单度中等”。RuntimeBasics 和本地模板可以快速起步；脚手架元数据统一后，Provider、依赖、装配表达式和配置样例不再分散在多个 switch 中。另一方面，使用者仍需要区分：

1. 纯 runtime 最小依赖、全量 Local Starter 和 Production Starter 三条入口，它们不是同一重量级别。
2. local、external-test、production profile 的失败策略和必填配置，它们不会因为 Provider 名称相似而自动兼容。
3. typed schema 的有效值、旧 ZeroConfig 的原始值和 Adapter 自己的配置规则；诊断视图能定位来源，但最终应收敛为一个消费契约。
4. CompletionStage 的返回类型和远程存储的真实阻塞语义；调用方仍需选择受管远程 IO 执行域。
5. ownership 是可选基础设施，不会自动把现有 LocalPlayerService、LocalRoomService 或 LocalWorldService 变成分布式服务。

因此，低心智负担的主要改进方向不是增加更多默认组件，而是让每个生成项目明确显示“当前使用的是 local、prototype、minimum-slice 还是 external Adapter”，并在 plan/diagnostics 中报告未接入的安全、容量、恢复和 owner 语义。

## 建议路线与验收条件

1. **P0：完成一条跨进程业务纵切片。** 选择 Player 会话或 Room 状态，复用 zero-ownership、PostgreSQL Adapter、现有 RPC/discovery 端口和 Actor lane；验收双 JVM 下 owner 抢占、epoch fencing、重复/迟到请求、checkpoint 恢复、节点退出和有界背压。单机调用方继续使用同一业务接口。
2. **P1：冻结远程 IO 语义。** 为 MongoDB、Redis、PostgreSQL 和 Kafka 标注同步/异步、超时、取消、关闭和恢复边界；提供受管 IO 域和有界队列测试，避免在 Actor 热路径内阻塞。
3. **P1：统一配置消费。** 以 RuntimeConfigDiagnostics 为只读诊断模型，明确文件、系统属性、环境变量、默认值和 Adapter decoder 的优先级；让诊断结果与 Provider 实际读取值一致。
4. **P1：建立 Adapter 契约测试矩阵。** local 与外部实现至少共同覆盖关闭、超时、幂等、版本冲突、错误码、资源回滚和健康状态；对 Kafka/Nacos 先补真实集群故障证据，再决定是否需要第二外部实现。
5. **P2：保持脚手架显式扩展。** 将标准 catalog 版本化并允许应用传入扩展目录，保留 Provider 冲突、未知 ID 和依赖闭包校验；不要引入无约束 classpath 扫描。
6. **P2：按真实需求增加单一实现替代品。** Netty、Kafka、Nacos、Prometheus 目前都是明确基线或唯一完整实现；只有出现可复现的部署、合规或容量需求时再引入第二 Adapter，同时复用已冻结契约。

这些建议可能触及 Actor、RPC、存储或公共 SPI，实施时需要单独的设计审查、迁移说明和验证证据。本文只记录评估和验收条件，不改变运行时行为。

## 证据、验证与限制

- 运行时与脚手架现状见[配置诊断与脚手架元数据统一](migrations/20260930-config-scaffold-flexibility.zh-CN.md)：RuntimeConfigDiagnostics 已提供安全有效配置视图，ScaffoldComponentDefinition 已统一组件元数据；这减少了装配分散，但没有提供自动插件发现。
- ownership 现状见[跨进程 ownership、迁移恢复与容量治理](migrations/20260930-distributed-ownership-recovery.zh-CN.md)：内存契约测试覆盖 lease/epoch、CAS、requestId、迁移恢复和容量预算；隔离 PostgreSQL 单实例双 JVM 验证了两个 store 竞争 ownership、旧 lease fencing、state replay、路由和迁移记录续转。
- 当前工作区已有的本地证据包括 Java 21 下 runtime、bootstrap、production、codegen 和 ownership 相关测试；外部 PostgreSQL 测试需要 ZERO_EXTERNAL_POSTGRES_URL、ZERO_EXTERNAL_POSTGRES_USER 和 ZERO_EXTERNAL_POSTGRES_PASSWORD_FILE，Kafka、Nacos、MongoDB、Redis 及数据库复制集未作为本报告的默认通过条件。
- 本轮没有把源码存在、模块能编译、单实例外部测试、双 JVM 测试、容量测试和生产就绪混为一谈。没有双 JVM/故障注入/长稳/容量证据的能力，统一按 prototype、minimum-slice 或 not-proven 处理。
- 现有[能力矩阵](capability-matrix.zh-CN.md)、[模块图](module-map.md)、[按需装配指南](guides/modular-composition-guide.zh-CN.md)和[Repository 指南](guides/repository-composition-guide.zh-CN.md)是本报告的交叉依据；历史报告只用于追踪日期和边界，不替代当前验证。
