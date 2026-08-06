# zero-rpc 用户注意事项与使用说明

`zero-rpc` 是 RPC 运行时模块，负责把 `zero-rpc-common` 中定义的强类型业务接口转换为字节级传输调用。它不依赖 Kafka、Nacos、Redis、MongoDB 等具体中间件。

当前主要能力：

- `RpcClientFactory`：创建 common 接口动态代理。
- `RpcServiceBinder`：把 common 接口实现绑定到 `RpcHandlerRegistry`。
- `RpcCodecRegistry`：注册 DTO 到协议 codec 的映射。
- `RpcPayloadCodec`：编码/解码 RPC 参数与结果。
- `RpcTransport`：字节级 request/response 与 oneway SPI。
- `InMemoryRpcTransport`：本地开发与单元测试传输实现。
- `RpcCallOptions`：设置 replyTopic、traceId、单次超时覆盖。
- `RpcCallContext`：在复用客户端代理时覆盖单次调用的 traceId、replyTopic 和超时。
- `RpcCorrelationIdGenerator`：生成请求/响应关联 ID，默认实现避免热路径 UUID 随机数开销。

## 一、使用前提

- 必须使用 Java 21。
- 调用方和服务方必须共享同一份 common 接口契约。
- 所有 RPC 参数 DTO 和非 `Void` 结果 DTO 必须先注册 codec。
- 业务代码优先通过 common 接口代理调用，不要直接拼 `RpcRequest`。
- 同步调用会等待 `CompletionStage` 完成，不要在 Netty IO 线程或 Actor 线程中长时间同步等待。

## 二、运行时调用链路

```text
调用方 common 接口
  -> RpcClientFactory 代理
  -> RpcPayloadCodec.encodeArguments
  -> RpcTransport.request / oneway
  -> RpcServiceBinder 绑定的 RpcHandler
  -> RpcPayloadCodec.decodeArguments
  -> 业务实现
  -> RpcPayloadCodec.encodeResult
  -> RpcResponse
  -> RpcPayloadCodec.decodeResult
  -> RpcResult<T>
```

## 三、注册 DTO codec

`zero-rpc` 复用 `zero-protocol` 的 `ProtocolCodec`。业务 DTO codec 通常由 codegen 生成；以下示例展示手写 codec 的完整形态。

```java
package group.zn.zero.example.rpc.runtime;

import group.zn.zero.example.rpc.common.PlayerKickDTO;
import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.codec.RpcCodecRegistry;

/**
 * 玩家 RPC codec 注册工具。
 *
 * @author zn
 */
public final class PlayerRpcCodecs {

    private PlayerRpcCodecs() {
    }

    /**
     * 创建并填充 RPC codec 注册表。
     *
     * @return codec 注册表；不为 null；线程安全。
     */
    public static RpcCodecRegistry createRegistry() {
        RpcCodecRegistry registry = new RpcCodecRegistry();
        registry.register(
                PlayerQueryDTO.class,
                new ProtocolDefinition(11001, "player.query", ProtocolDirection.SERVER_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(PlayerQueryCodec.INSTANCE));
        registry.register(
                PlayerProfileDTO.class,
                new ProtocolDefinition(11002, "player.profile", ProtocolDirection.SERVER_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(PlayerProfileCodec.INSTANCE));
        registry.register(
                PlayerKickDTO.class,
                new ProtocolDefinition(11003, "player.kick", ProtocolDirection.SERVER_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(PlayerKickCodec.INSTANCE));
        return registry;
    }

    /**
     * 玩家查询请求 codec。
     *
     * @author zn
     */
    public enum PlayerQueryCodec implements ZeroPayloadCodec<PlayerQueryDTO> {

        /**
         * 单例。
         */
        INSTANCE;

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不为 null；线程安全。
         */
        @Override
        public String name() {
            return "PlayerQueryCodec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不为 null；线程安全。
         */
        @Override
        public Class<PlayerQueryDTO> messageType() {
            return PlayerQueryDTO.class;
        }

        /**
         * 写入查询请求。
         *
         * @param writer 写入器；不可为 null。
         * @param message 查询请求；不可为 null。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerQueryDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.playerId());
            writer.endObject(marker);
        }

        /**
         * 读取查询请求。
         *
         * @param reader 读取器；不可为 null。
         * @return 查询请求；不为 null；线程不安全。
         */
        @Override
        public PlayerQueryDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            long playerId = 0L;
            if (reader.hasRemainingInObject(end)) {
                playerId = reader.readLong();
            }
            reader.endObject(end);
            return new PlayerQueryDTO(playerId);
        }
    }

    /**
     * 玩家概要响应 codec。
     *
     * @author zn
     */
    public enum PlayerProfileCodec implements ZeroPayloadCodec<PlayerProfileDTO> {

        /**
         * 单例。
         */
        INSTANCE;

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不为 null；线程安全。
         */
        @Override
        public String name() {
            return "PlayerProfileCodec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不为 null；线程安全。
         */
        @Override
        public Class<PlayerProfileDTO> messageType() {
            return PlayerProfileDTO.class;
        }

        /**
         * 写入玩家概要。
         *
         * @param writer 写入器；不可为 null。
         * @param message 玩家概要；不可为 null。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerProfileDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.playerId());
            writer.writeString(message.name());
            writer.endObject(marker);
        }

        /**
         * 读取玩家概要。
         *
         * @param reader 读取器；不可为 null。
         * @return 玩家概要；不为 null；线程不安全。
         */
        @Override
        public PlayerProfileDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            long playerId = 0L;
            String name = "";
            if (reader.hasRemainingInObject(end)) {
                playerId = reader.readLong();
            }
            if (reader.hasRemainingInObject(end)) {
                name = reader.readString();
            }
            reader.endObject(end);
            return new PlayerProfileDTO(playerId, name);
        }
    }

    /**
     * 玩家踢下线请求 codec。
     *
     * @author zn
     */
    public enum PlayerKickCodec implements ZeroPayloadCodec<PlayerKickDTO> {

        /**
         * 单例。
         */
        INSTANCE;

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不为 null；线程安全。
         */
        @Override
        public String name() {
            return "PlayerKickCodec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不为 null；线程安全。
         */
        @Override
        public Class<PlayerKickDTO> messageType() {
            return PlayerKickDTO.class;
        }

        /**
         * 写入踢下线请求。
         *
         * @param writer 写入器；不可为 null。
         * @param message 踢下线请求；不可为 null。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerKickDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.playerId());
            writer.writeString(message.reason());
            writer.endObject(marker);
        }

        /**
         * 读取踢下线请求。
         *
         * @param reader 读取器；不可为 null。
         * @return 踢下线请求；不为 null；线程不安全。
         */
        @Override
        public PlayerKickDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            long playerId = 0L;
            String reason = "";
            if (reader.hasRemainingInObject(end)) {
                playerId = reader.readLong();
            }
            if (reader.hasRemainingInObject(end)) {
                reason = reader.readString();
            }
            reader.endObject(end);
            return new PlayerKickDTO(playerId, reason);
        }
    }
}
```

## 四、本地闭环调用示例

`InMemoryRpcTransport` 适合单元测试、最小原型和本地 smoke，不代表分布式可靠性。

```java
package group.zn.zero.example.rpc.runtime;

import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.example.rpc.server.PlayerRemoteRpcImpl;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import group.zn.zero.rpc.server.RpcBoundService;
import group.zn.zero.rpc.server.RpcServiceBinder;

/**
 * 本地 RPC 闭环示例。
 *
 * @author zn
 */
public final class LocalRpcExample {

    private LocalRpcExample() {
    }

    /**
     * 执行本地闭环调用。
     *
     * @return 玩家名称；不为 null；线程安全。
     */
    public static String run() {
        RpcCodecRegistry codecRegistry = PlayerRpcCodecs.createRegistry();
        InMemoryRpcTransport transport = new InMemoryRpcTransport();

        RpcServiceBinder binder = new RpcServiceBinder(transport, codecRegistry);
        RpcBoundService boundService = binder.bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());

        RpcCallOptions options = RpcCallOptions.defaults()
                .withReplyTopic("local-reply")
                .withTimeoutMillis(2000);
        PlayerRemoteRpc client = new RpcClientFactory(transport, codecRegistry, options)
                .create(PlayerRemoteRpc.class);

        RpcResult<PlayerProfileDTO> result = group.zn.zero.rpc.RpcCallContext.with(
                group.zn.zero.rpc.RpcCallContext.empty().withTraceId("trace-local-player-query"),
                () -> client.queryPlayer(new PlayerQueryDTO(10086L)));
        try {
            return result.orThrow().name();
        } finally {
            boundService.close();
        }
    }
}
```

## 五、异步调用示例

```java
package group.zn.zero.example.rpc.runtime;

import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.rpc.common.RpcResult;
import java.util.concurrent.CompletionStage;

/**
 * 异步 RPC 调用示例。
 *
 * @author zn
 */
public final class AsyncRpcExample {

    private AsyncRpcExample() {
    }

    /**
     * 异步查询玩家。
     *
     * @param client RPC 客户端代理；不可为 null。
     * @param playerId 玩家 ID。
     * @return 玩家名称异步结果；不为 null；线程安全。
     */
    public static CompletionStage<String> queryName(final PlayerRemoteRpc client, final long playerId) {
        return client.queryPlayerAsync(new PlayerQueryDTO(playerId))
                .thenApply(RpcResult::orThrow)
                .thenApply(PlayerProfileDTO::name);
    }
}
```

注意：

- 回调线程由传输实现决定。
- 回调内不要直接修改玩家、场景等线程绑定状态。
- 如需修改 Actor 绑定状态，应在回调中投递 Actor 消息。

## 六、oneway 调用示例

```java
package group.zn.zero.example.rpc.runtime;

import group.zn.zero.example.rpc.common.PlayerKickDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.rpc.common.RpcResult;

/**
 * oneway RPC 调用示例。
 *
 * @author zn
 */
public final class OnewayRpcExample {

    private OnewayRpcExample() {
    }

    /**
     * 发送踢下线通知。
     *
     * @param client RPC 客户端代理；不可为 null。
     * @param playerId 玩家 ID。
     * @return true 表示发送完成；不代表远端业务成功。
     */
    public static boolean kick(final PlayerRemoteRpc client, final long playerId) {
        RpcResult<Void> result = client.kickPlayer(new PlayerKickDTO(playerId, "duplicate login"));
        return result.success();
    }
}
```

## 七、错误处理示例

```java
package group.zn.zero.example.rpc.runtime;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.rpc.common.RpcResult;

/**
 * RPC 错误处理示例。
 *
 * @author zn
 */
public final class RpcErrorHandlingExample {

    private RpcErrorHandlingExample() {
    }

    /**
     * 查询玩家并处理失败。
     *
     * @param client RPC 客户端代理；不可为 null。
     * @param playerId 玩家 ID。
     * @return 玩家概要；失败时抛出绑定 ErrorCode 的 ZeroException。
     */
    public static PlayerProfileDTO queryOrThrow(final PlayerRemoteRpc client, final long playerId) {
        RpcResult<PlayerProfileDTO> result = client.queryPlayer(new PlayerQueryDTO(playerId));
        try {
            return result.orThrow();
        } catch (ZeroException ex) {
            // 生产代码应进入统一异常处理、错误日志和指标，不应只打印后继续。
            throw ex;
        }
    }
}
```

## 八、底层传输 SPI 示例

业务代码通常不应直接使用 `RpcTransport`。只有开发新 Adapter 或写极小 smoke 时才直接调用。

```java
package group.zn.zero.example.rpc.runtime;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 直接传输 SPI 示例。
 *
 * @author zn
 */
public final class RawTransportExample {

    private RawTransportExample() {
    }

    /**
     * 执行原始 request/response。
     *
     * @return 响应文本；不为 null。
     */
    public static String rawRequest() {
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        transport.register("demo", "echo", request -> java.util.concurrent.CompletableFuture.completedFuture(
                new RpcResponse(
                        request.correlationId(),
                        request.traceId(),
                        SystemErrorCode.OK,
                        request.payload())));

        RpcRequest request = new RpcRequest(
                "corr-demo",
                "reply-demo",
                "demo",
                "echo",
                "trace-demo",
                Instant.now().plusSeconds(3),
                RpcMode.REQUEST_RESPONSE,
                "hello".getBytes(StandardCharsets.UTF_8));

        RpcResponse response = transport.request(request).toCompletableFuture().join();
        return new String(response.payload(), StandardCharsets.UTF_8);
    }
}
```

## 九、风险与缓解

### 客户端复用与 traceId 风险

风险：`RpcClientFactory` 会复用相同接口和相同 `RpcCallOptions` 的客户端代理。如果把每次请求变化的 traceId 固定写入 `RpcCallOptions`，要么导致代理缓存命中率下降，要么让多个调用复用同一个 traceId。

缓解：

- `RpcCallOptions` 只放 replyTopic、默认 traceId 和默认超时。
- 每次入口请求使用 `RpcCallContext.with(...)` 覆盖 traceId。
- `RpcCallContext` 是 ThreadLocal，不会自动跨线程传播；跨线程发起 RPC 时显式捕获并包裹。

示例：

```java
PlayerRemoteRpc client = factory.create(PlayerRemoteRpc.class);
RpcResult<PlayerProfileDTO> result = RpcCallContext.with(
        RpcCallContext.empty().withTraceId(traceId).withTimeoutMillis(1500),
        () -> client.queryPlayer(new PlayerQueryDTO(playerId)));
```

### correlationId 生成风险

风险：高频调用中使用 `UUID.randomUUID()` 会增加随机数和字符串分配成本；自定义生成器若不保证实例内唯一，可能造成响应串线或旧超时任务干扰。

缓解：

- 默认使用 `DefaultRpcCorrelationIdGenerator`。
- 自定义 `RpcCorrelationIdGenerator` 必须线程安全，并保证同一 caller 实例内不重复。
- 需要按节点定位时，可用 `new DefaultRpcCorrelationIdGenerator(nodeId)` 注入节点前缀。

### 同步等待风险

风险：同步代理方法会等待远端响应；在 IO 线程或 Actor 线程中调用可能导致阻塞、排队和超时放大。

缓解：

- 高频和跨服调用优先使用 `CompletionStage<RpcResult<T>>`。
- 在 Actor 线程内只发起异步调用，结果回调再投递 Actor 消息。
- 为每个方法设置合理 `timeoutMillis`。

### codec 缺失风险

风险：参数 DTO 或结果 DTO 未注册 codec，会在代理创建或绑定阶段抛出 `CODEC_NOT_FOUND`。

缓解：

- 启动阶段统一创建并校验 `RpcCodecRegistry`。
- 为每个 common 接口写本地闭环测试。
- 使用 codegen 生成 codec，减少手写错误。

### payload null 风险

风险：当前 `RpcPayloadCodec` 不允许参数或非 Void 结果为 null。

缓解：

- 使用空 DTO 表示无参数。
- 使用 `RpcResult<Void>` 表示无返回值。
- DTO 内字段使用协议默认值，不把整个 DTO 置 null。

### 传输异常语义风险

风险：远端异常、codec 异常、超时和传输不可用都会转换为 `RpcResult.failure(...)` 或异常完成的 `CompletionStage`。

缓解：

- 调用侧统一检查 `RpcResult.success()` 或使用 `orThrow()`。
- 异步调用使用 `exceptionally` / `handle` 兜底。
- 错误日志必须记录 traceId、correlationId、serviceName、methodName 和 ErrorCode。

### InMemory 误用风险

风险：`InMemoryRpcTransport` 不模拟 Kafka 消费组、网络抖动、消息重复、rebalance、broker 不可用。

缓解：

- 本地单元测试使用 InMemory。
- Kafka 行为使用 `zero-rpc-kafka` 内存 gateway 测试。
- 上线前增加真实 broker external test。

## 十、场景示例附录

### 场景 1：启动时绑定服务

```java
RpcCodecRegistry codecs = PlayerRpcCodecs.createRegistry();
InMemoryRpcTransport transport = new InMemoryRpcTransport();
RpcBoundService bound = new RpcServiceBinder(transport, codecs)
        .bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
```

风险：重复绑定同一路由会覆盖旧 handler。

缓解：启动阶段集中注册，服务关闭时调用 `bound.close()`。

### 场景 2：创建客户端代理

```java
PlayerRemoteRpc client = new RpcClientFactory(
        transport,
        codecs,
        RpcCallOptions.defaults()
                .withReplyTopic("reply-node-1")
                .withTimeoutMillis(2000))
        .create(PlayerRemoteRpc.class);
```

风险：把 traceId 固定到 `RpcCallOptions` 适合示例默认值，不适合生产入口请求。

缓解：生产中从入口请求、消息上下文或 Trace 模块生成 traceId，并用 `RpcCallContext` 包裹单次调用。

```java
RpcResult<PlayerProfileDTO> result = RpcCallContext.with(
        RpcCallContext.empty().withTraceId(traceId),
        () -> client.queryPlayer(new PlayerQueryDTO(playerId)));
```

### 场景 3：同步调用

```java
PlayerProfileDTO profile = client.queryPlayer(new PlayerQueryDTO(10086L)).orThrow();
```

风险：同步等待会占用当前线程。

缓解：边缘后台或低频 GM 可同步；核心 Actor 和 IO 链路优先异步。

### 场景 4：异步调用

```java
CompletionStage<String> nameStage = client.queryPlayerAsync(new PlayerQueryDTO(10086L))
        .thenApply(RpcResult::orThrow)
        .thenApply(PlayerProfileDTO::name);
```

风险：回调线程不一定是业务绑定线程。

缓解：在回调中投递事件或 Actor 消息，不直接改玩家/场景状态。

### 场景 5：原始传输 SPI

```java
CompletionStage<RpcResponse> stage = transport.request(request);
```

风险：业务代码直接操作 `RpcRequest` 容易绕过 codec、契约校验和 trace 规范。

缓解：业务默认使用 common 接口代理；原始 SPI 只用于 Adapter 开发和测试。
