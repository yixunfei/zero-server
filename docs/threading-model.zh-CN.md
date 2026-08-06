# 线程模型设计

## 1. 目标

zeroServer 的线程模型必须同时满足极致性能、状态安全、业务开发简单和单进程/分布式平滑切换。

默认线程类型：

- Netty IO 线程。
- 逻辑 Actor 线程。
- 可异步远程 IO 线程。
- 定时任务线程。
- 低频后台/GM/远程 IO 虚拟线程。

## 2. 核心规则

- 业务代码绝不允许直接创建线程池。
- 所有线程池必须由框架统一管理。
- 玩家、实体、场景状态修改必须基于线程绑定安全修改。
- 跨 Actor 调用必须通过消息，不允许直接引用修改。
- IO 线程不允许执行业务阻塞逻辑。
- Actor 线程不允许执行不可控远程 IO。
- 线程安全 API 与非线程安全 API 必须在注释中明确标明。

## 3. Actor 绑定

默认 lane key 包括：

- serverId。
- playerId。
- entityId。
- sceneId。
- sessionId。
- eventId。
- customKey。

默认模型：

- 玩家在线数据绑定 player actor。
- 场景状态绑定 scene actor。
- 跨玩家、跨场景大部分操作接受最终一致性。
- 充值订单、账号、货币等重要数据必须支持强一致策略。

当前阶段 1 已提供 `LocalActorScheduler` 作为本地确定性实现：

- 通过 `ActorScheduler.register` 按消息体类型注册处理器。
- 通过 `ActorScheduler.dispatch` 提交 `ActorMessage`。
- 同一 `LaneKey` 内消息按提交顺序串行执行。
- 本地实现不创建线程池，处理逻辑在调用线程中推进。
- `ActorContext` 会向处理器传递 lane key、messageId 和 traceId。

阶段 3 新增 `ExecutorActorScheduler` 作为 executor-backed Actor 调度实现：

- 调度器本身不创建线程池，只使用外部传入的 `Executor`。
- 同一 `LaneKey` 内消息按提交顺序串行执行。
- handler 返回异步 `CompletionStage` 时，后续同 lane 消息必须等待前一条完成后再执行。
- 该实现用于阶段 3 原型执行域探索，不替代后续生产级 Actor 集群、背压、限流和队列监控设计。

阶段 4B 新增分布式 Actor gateway 的第一版投递边界：

- `LaneKey` 仍只表示线程绑定对象，不承载远程地址、topic、consumer group、实例 ID 或服务发现细节。
- `ActorAddress` 表达 Actor 身份和远程归属服务；本地地址的 owner service 为空。
- `ActorRoute` 表达本地或远程投递决策，远程 route 可携带 transport、request topic、consumer group、partition key 和扩展 metadata。
- `ActorRouteResolver` 只做消息到 route 的选择，不读取或修改远程 Actor 状态。
- `RemoteActorGateway` 只负责远程消息投递，不暴露远程 Actor 状态引用。
- `GameActorGateway.dispatch(context, laneKey, command)` 既有语义保持：业务仍只提交消息，不直接修改其他 Actor 状态。
- `zero-rpc` 的 `RpcRemoteActorGateway` 第一版使用 oneway RPC 承载状态修改消息，发送完成不代表远端业务状态已经处理完成。
- `RpcRemoteActorReceiver` 在接收端把 RPC payload 解码为 `ActorMessage` 后投递到本地 `ActorScheduler`，状态修改仍发生在接收端 Actor handler 内。
- 远程状态修改消息默认不自动重试；需要幂等时应显式携带 messageId / idempotency key，并由业务处理重复投递风险。
- Actor handler 内仍不得同步等待远程 RPC；如需远程结果，应在远程 IO 完成后投递新的 Actor 消息。

阶段 2 技术债收口后，`zero-server-starter` 新增 `ZeroRuntimeExecutors` 作为统一线程管理的装配点；阶段 3 已扩展为：

- 默认本地装配使用 direct executor，不额外创建后台线程。
- 完整本地 demo 可以显式使用 starter 管理的单线程逻辑执行器，验证业务 handler 不运行在 Netty IO 线程中。
- `localPrototype` 可以创建 logic、actor、remote IO 和 background 执行域，供阶段 3 业务模块直接接入。
- `singleThreaded` 为避免自等待，Actor 执行仍使用 direct executor；需要独立 actor 线程池时应使用 `localPrototype`。
- 该装配点不替代生产级统一线程管理；Netty IO、Kafka consumer worker、Kafka pending 时间轮、Actor 队列背压、持久化 flush 调度和虚拟线程策略仍必须在后续专项中统一设计。

阶段 3 原型默认绑定：

- `zero-player` 登录会话写入 session lane。
- `zero-player` 玩家在线档案写入 player lane；玩家加载可通过 `CacheService` / `CrudRepository` 获取数据，真实远程实现接入前必须放入 remote IO 执行域。
- `zero-scene` 进入场景、实体坐标、移动、离开和实体列表快照写入或读取 scene lane。
- `docs/scene-move-loop-performance-evidence.zh-CN.md` 与 `scripts/ZeroSceneMoveLoopBenchmarkReadiness.java` 已定义基础 SceneService、Local/Executor scheduler、generated scene-sync 和未来 AOI/广播的分层性能证据口径；该入口不运行场景服务，也不批准线程模型或移动语义变更。
- GM 查询首轮只读，通过 player lane 或 scene lane 获取快照，不直接跨 lane 修改状态。
- 远程 IO 必须先在 remote IO 执行域完成，再把结果投递回对应 actor lane。

### 3.1 本地受管定时任务边界

`zero-core` 已提供 `ManagedScheduler`、异步 `ScheduledTask`、句柄、快照、事件和 ErrorCode 中立契约，`zero-server-starter` 提供默认关闭的 `LocalManagedScheduler` minimum-slice：

- 单 timer thread 只做到期判断、状态 CAS、容量判断和一次性 worker 提交，不运行用户任务、observer、日志或指标。
- 用户任务和 observer 进入 Starter 受管且非内联的 background executor；`direct()` 和当前 `singleThreaded(...)` 在启用 scheduler 时会被工厂拒绝。
- `ScheduledTask.execute` 返回非空 `CompletionStage<Void>`；in-flight 预算覆盖已提交、执行中和未完成 stage 的完整生命周期。
- fixed-delay 从 stage 真正完成后计算下一次延迟。
- fixed-rate 以 monotonic deadline 和一次性 timer 自重排；运行中、容量不足或已经错过的节拍只聚合 skip，不排队、不补跑、不追赶。
- cancel 不 interrupt 已进入用户代码的任务，也不强制取消用户 stage，只阻止尚未开始和未来触发。
- 玩家、场景和实体状态仍必须把 `ScheduledTaskContext.traceId()` 显式传入 `ActorMessage`，在对应 lane 内修改。
- 远程 IO 只提交到受管 remote IO 执行域或异步客户端并返回 stage，禁止在 scheduler worker 上 `join()` / `get()`。

该实现不替换 Netty EventLoop timer、Kafka RPC pending 时间轮或 `PersistenceScheduler` 既有局部语义，也不适用于生产高频 tick、cron、持久化任务或分布式唯一执行。完整 API、配置、L1 生命周期、观测和非目标见 [本地受管定时任务运行时](managed-scheduler.zh-CN.md)。

## 4. 强一致策略

强一致不采用单一方案，按场景选择：

- 单 Actor 串行。
- 数据库事务。
- 分布式锁。
- 乐观锁版本号。
- 补偿事务。
- 审计与人工修复。

## 5. 虚拟线程使用边界

Java 21 虚拟线程允许用于：

- 低频后台任务。
- GM 查询。
- 管理 API。
- 远程 IO 边缘适配。

不应默认用于：

- 核心 Actor 热路径。
- 高频协议处理。
- 高频广播。
- 场景 Tick。

## 6. 生产 TCP 生命周期线程边界

显式启用 `ProductionNetworkLifecycle` 后，单连接状态、握手等待队列、心跳计数和 in-flight 预算只允许在该连接的 Netty EventLoop 访问。线程边界如下：

- Netty IO 线程只执行状态推进、握手轻量校验、心跳识别、限流判断和有界队列操作。
- `ProductionNetworkPolicy.authenticate` 与 `coordinateReconnect` 必须由 starter 管理的非内联 remote IO executor 调用；production factory 会拒绝可能内联的执行器。
- 业务 `ServerFrameHandler` 仍使用调用方传入的业务 executor，不在 Netty IO 线程运行。
- 日志和指标 observer 复用 starter 管理的共享 background executor；每个连接通过私有有序 drain 最多提交一个活动排空任务，同一连接按会话提交顺序执行，不同连接仍可并发。该 drain 不创建线程池，也不要求共享 executor 自身为单线程。
- observer 失败通知必须回到连接 EventLoop 后再进入 `ConnectionListener`。当前每连接 observer 待执行队列没有独立容量上限、背压或丢弃策略，慢 observer 可能积压，因此有序执行不等于容量、长稳或故障降级已经验证。
- 重连若涉及玩家在线状态，只允许由 `coordinateReconnect` 实现向 player actor 发送消息，不允许网络线程或 remote IO 线程直接修改玩家状态。
- observer、鉴权或业务完成回调必须先投递回 EventLoop，再修改连接生命周期状态或释放 in-flight 预算。

该实现没有在 `zero-net` 中创建业务线程池，但 `NettyTcpServer` 当前仍自行管理 Netty IO 线程组；把 Netty EventLoop 纳入 starter 全局线程资源管理属于后续独立高风险任务。
