# RPC 设计

## 1. 目标

zeroServer RPC 偏向服务接口调用，首版可调用契约支持 request/response 和 oneway；broadcast 仍是规划能力，不在当前 common 接口枚举中暴露。

RPC 编解码优先使用自研协议，但必须抽象 SPI，支持未来平滑替换 Cap'n Proto。

## 2. Kafka RPC

zeroServer 支持同步 RPC 通过 Kafka，但默认鼓励异步回调通知。

同步 RPC 默认超时：

```text
3 秒，可配置
```

Kafka 不可用时：

- 同步 RPC 默认失败。
- 直到心跳监听到 Kafka 恢复可用。
- 不进行无限排队。

当前 `zero-rpc-kafka` 以 `KafkaRpcAdapter` 实现 `RpcTransport` 和 `RpcHandlerRegistry`：

- 调用方 common 接口代理仍只依赖 `zero-rpc` 抽象。
- 服务方 common 接口实现通过 `RpcServiceBinder` 绑定到 Kafka adapter。
- request topic 按服务路由生成，reply topic 按调用方实例路由。
- `@RpcService.topic` 可覆盖 request topic，`@RpcService.group` 可覆盖 provider consumer group。
- `@RpcMethod.partitionKey` 在代理创建阶段校验并缓存访问器，调用时作为 Kafka message key。
- `correlationId` 由 pending 请求表映射响应，pending 有容量限制和超时清理。
- `RpcClientFactory` 默认复用相同接口与相同 `RpcCallOptions` 的客户端代理；每次调用变化的 `traceId`、`replyTopic` 和超时应通过 `RpcCallContext` 覆盖。
- `correlationId` 默认由 `DefaultRpcCorrelationIdGenerator` 生成，避免在调用热路径使用 `UUID.randomUUID()`。
- `KafkaRpcPendingRequests` 使用内部时间轮管理 pending 超时，避免为每个请求创建独立 `ScheduledFuture`。
- 消费端收到超过 `timeoutAt` 的请求会拒绝执行业务 handler，并对 request/response 返回错误响应。
- oneway 只等待 Kafka send 完成，不等待业务响应。
- `zero-rpc` 提供 `RpcTransportObserver`、`RpcTransportEvent`、`RpcTransportEventType` 和 `RpcTransportSnapshot` 作为传输观测 SPI，不绑定日志、指标或具体中间件。
- `KafkaRpcAdapter` 会在 request/response、oneway、pending 注册/拒绝/完成/失败/超时、消费端拒绝、handler 执行、响应发送和 consumer 重启路径发出观测事件，并暴露只读传输快照；pending 失败和超时事件保留 traceId、serviceName 与 methodName，便于生产排障。
- broadcast 首版暂不提升到 `RpcTransport` 公共 SPI。

## 3. RPC 标准字段

RPC 必须包含：

- correlationId。
- replyTopic。
- serviceName。
- methodName。
- traceId。
- timeoutAt。

建议包含：

- sourceServerId。
- targetServerId。
- group。
- version。
- payloadCodec。
- retryPolicy。

## 3.1 common 接口 RPC 调用面

RPC 的业务调用面以 common 接口为管理边界，而不是新增 `.si server_to_server:` 或独立
`rpc:` 方法区。RPC 双端依赖同一套 `zero-*-rpc-common-interfaces` 契约模块，调用方拿接口代理，
远端实现该接口。

推荐形态：

- common 接口使用 `@RpcService` 标识服务名和版本。
- common 接口方法使用 `@RpcMethod` 标识稳定 methodId、超时、调用模式和幂等标记。
- 调用方使用 `RpcClientFactory` 创建接口代理。
- 服务方实现同一个 common 接口，并通过 `RpcServiceBinder` 绑定到 `RpcTransport`。
- 业务代码不直接拼 `serviceName`、`methodName`、`byte[]` 或 `RpcRequest`。
- DTO 与 payload 的转换在 RPC runtime 内部完成，编解码复用 proto 模板生成的 `ProtocolCodec` / `ZeroPayloadCodec`。
- Kafka、内存传输或后续其他中间件只实现 `RpcTransport`，不泄漏到 common 接口。

`RpcTransport` 保持字节级传输 SPI；强类型 RPC 由 `zero-rpc-common` 契约 API 和 `zero-rpc` 运行时组合提供。

首版接口返回值只支持：

- `RpcResult<T>`
- `CompletionStage<RpcResult<T>>`

直接返回 `T`、复杂泛型、可变参数和无 timeout 同步调用暂不作为默认能力。

## 4. 超时与执行语义

默认语义：

- 请求超过 `timeoutAt` 后，消费端应拒绝执行。
- 调用方超时后不等待响应。
- 调用方 pending 超时由时间轮触发，精度以 tick 为边界；超时任务与完成/失败操作按 pending 表幂等删除。
- 业务仍应具备幂等意识，因为分布式系统存在边界竞争。

`RpcCallContext` 使用 ThreadLocal 保存当前调用线程上下文，适合在入口请求、消息消费、GM 指令等边界包裹单次 RPC 调用：

```java
RpcCallContext.with(
        RpcCallContext.empty().withTraceId(traceId).withReplyTopic(replyTopic),
        () -> client.queryPlayer(request));
```

跨线程或异步回调中不会自动继承该上下文；需要跨线程发起 RPC 时，应显式捕获并在目标线程重新包裹。

如果业务需要“超时后仍可执行”，必须使用高度辨识的专用接口，并在接口命名和注释中明确说明。

## 5. Broadcast

Broadcast RPC 支持两种筛选：

- 业务逻辑层传入目标集合。
- 基于中间件分组订阅筛选节点。

二者必须在 API 上明确区分，避免误广播。

## 6. 服务发现

服务发现是 RPC 的可选协作输入，不改变 `RpcTransport` 的 request/response、oneway 和后续 broadcast 请求语义。

当前服务发现边界采用同一套 `ServiceDiscovery` API 支持本地注册表、配置文件派生注册表和 Nacos Adapter。Nacos 面向 3.x，必须显式配置 namespace；group、cluster、ephemeral、enabled、weight 和健康状态属于服务发现实例或查询语义，不能塞入 RPC 请求字段。

`zero-rpc` 不依赖 `zero-discovery-nacos`，提供中立 RPC discovery 模型和 resolver：

- `RpcServiceInstance`
- `RpcServiceQuery`
- `RpcServiceSnapshot`
- `RpcServiceSelection`
- `RpcServiceResolver`
- `RoundRobinRpcServiceResolver`

这些模型只描述“可调用实例”和“如何选择实例”，不连接 Nacos，不发送 RPC 请求，不改变 Kafka envelope。

`RpcDiscoveryMetadata` 约定 RPC provider 可写入服务实例 metadata 的轻量字段：

- `zero.rpc.serviceName`
- `zero.rpc.serviceVersion`
- `zero.rpc.transport`
- `zero.rpc.requestTopic`
- `zero.rpc.consumerGroup`
- `zero.rpc.instanceId`
- `zero.rpc.protocolVersion`
- `zero.rpc.zone`

旧版 `zero.rpc.version`、`zero.rpc.topic`、`zero.rpc.group` 和 `zero.rpc.protocol` 保留兼容读取和写入。

`zero-discovery-nacos` 提供 `NacosRpcMetadataMapper` 和 `ServiceDiscoveryRpcServiceResolver`，负责把 Nacos / 本地 `ServiceDiscovery` 实例映射为 RPC 中立模型。`zero-server-starter-production` 在显式启用 Nacos discovery 时暴露 `rpcServiceResolver()`；默认 local 路径仍不连接 Nacos。

consumer 侧默认调用路径仍由 `RpcClientFactory`、`RpcRoute` 和具体 transport 决定。本阶段先提供显式 resolver 协作入口，不把服务发现自动接入所有 RPC 调用，避免改变现有调用语义。

## 6.1 Actor gateway RPC 桥接

分布式 Actor gateway 第一版复用 RPC transport 作为远程投递通道，但不把 Kafka、Nacos 或具体服务发现细节引入 `zero-game`：

- `zero-actor` 定义 `ActorAddress`、`ActorRoute`、`ActorDispatchOptions`、`ActorRouteResolver` 和 `RemoteActorGateway` 中立模型。
- `zero-game` 的 `GameActorGateway` 保持既有 `dispatch(context, laneKey, command)` 语义；默认构造仍只投递本地 scheduler，远程 route 必须显式装配。
- `zero-rpc` 提供 `RpcActorMessageCodec`，把 `ActorMessage` 的 messageId、laneKey、traceId、payload 类型和 payload 字节编码成 RPC payload；业务 payload 仍由 `RpcCodecRegistry` 中注册的 `ProtocolCodec` 编解码。
- `RpcRemoteActorGateway` 使用 oneway `RpcTransport` 发送远程 Actor 消息；send 完成只表示消息交给传输层，不表示接收端 Actor handler 已完成状态修改。
- `RpcRemoteActorReceiver` 在接收端通过 `RpcHandlerRegistry` 注册 dispatch handler，解码后投递到本地 `ActorScheduler`。
- `RpcActorRouteResolver` 可把 `RpcServiceResolver` 选择出的 `RpcServiceInstance` 转为远程 `ActorRoute`，从而复用 Nacos RPC metadata 中的 request topic、consumer group、transport、instanceId 和 zone。

边界规则：

- `LaneKey.value` 不保存远程地址、topic、实例 ID 或服务发现字段。
- 远程 Actor 状态不可直接引用，跨 Actor 修改仍必须通过消息。
- Actor handler 内不得同步等待远程 RPC。
- 远程状态修改消息默认不自动重试；如果业务允许重试，必须显式设计幂等键、去重窗口和补偿策略。
- Kafka RPC envelope 不因 Actor gateway 改变；Actor 信封只是 RPC payload 内部格式。

## 7. 当前实现快照

- `zero-rpc-common` 当前提供 `RpcService`、`RpcMethod`、`RpcCallMode` 和 `RpcResult`，用于业务 common 接口契约。
- `zero-rpc` 当前提供 `RpcRequest`、`RpcResponse`、`RpcMode`、`RpcCallOptions`、`RpcCallContext`、`RpcCorrelationIdGenerator`、`DefaultRpcCorrelationIdGenerator`、`RpcHandler`、`RpcRoute`、`RpcTransport`、`InMemoryRpcTransport`、`RpcClientFactory`、`RpcServiceBinder`、`RpcCodecRegistry`、`RpcServiceIntrospector`、`RpcDiscoveryMetadata`、`RpcServiceInstance`、`RpcServiceQuery`、`RpcServiceResolver`、`RoundRobinRpcServiceResolver`、`RpcTransportObserver`、`RpcTransportEvent`、`RpcTransportSnapshot` 以及 `group.zn.zero.rpc.actor` 下的 RPC Actor gateway 桥接组件。
- common 接口本地闭环已按 `接口代理 -> ProtocolCodec 编码 -> RpcRequest -> 远端实现 -> RpcResponse -> ProtocolCodec 解码` 形态落地。
- `InMemoryRpcTransport` 支持 request/response 和 oneway，并在本地链路里校验 `timeoutAt`、`correlationId` 和 `traceId`。
- `zero-rpc-kafka` 当前提供 `KafkaRpcAdapter`、`KafkaRpcSettings`、`KafkaRpcEnvelopeCodec`、`KafkaRpcTopicResolver`、`KafkaRpcPendingRequests` 和内部 `KafkaRpcTimeoutWheel`，已接入 Apache Kafka producer/consumer gateway，并支持默认单元测试使用内存 gateway 验证；Kafka adapter 已发出 RPC 观测事件并暴露 pending、发送、拒绝和 consumer 重启计数快照。
- `docs/rpc-pending-performance-evidence.zh-CN.md` 与 `scripts/ZeroRpcPendingBenchmarkReadiness.java` 已整理 pending 注册/完成、并发容量、时间轮误差、observer、fail-all 和发送失败的 benchmark 前置口径；该入口不启动时间轮、不连接 Kafka，也不证明生产容量。
- 当前 `replyTopic`、`serviceName`、`methodName`、`traceId`、`timeoutAt`、`topic`、`group` 和 `partitionKey` 已进入 RPC/Kafka 路由链路；RPC discovery 模型只描述服务发现实例 metadata 和显式选择结果，不改变 RPC/Kafka envelope 和 transport 请求语义。Kafka/Nacos 多 JVM external-test 已覆盖远程 Actor caller JVM 通过 Nacos 解析 Kafka route 后向 provider JVM 投递 Actor 消息的最小闭环。
