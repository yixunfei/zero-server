package group.zn.zero.rpc.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.rpc.DefaultRpcCorrelationIdGenerator;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import group.zn.zero.rpc.server.RpcBoundService;
import group.zn.zero.rpc.server.RpcServiceBinder;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * common 接口 RPC 闭环测试。
 *
 * @author zn
 */
class CommonInterfaceRpcTest {

    /**
     * 验证同步 common 接口调用可以通过本地 RPC 传输完成。
     */
    @Test
    void commonInterfaceShouldInvokeRemoteImplementation() {
        RpcCodecRegistry codecRegistry = codecRegistry();
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        RpcServiceBinder binder = new RpcServiceBinder(transport, codecRegistry);
        RpcBoundService boundService = binder.bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        PlayerRemoteRpc client = new RpcClientFactory(
                transport,
                codecRegistry,
                RpcCallOptions.defaults().withTraceId("trace-common"))
                .create(PlayerRemoteRpc.class);

        PlayerQueryResponseDTO response = client.queryPlayer(new PlayerQueryRequestDTO(10086L)).orThrow();

        assertEquals(10086L, response.uid);
        assertEquals("player-10086", response.name);
        assertTrue(boundService.bound());
        boundService.close();
        assertFalse(boundService.bound());
    }

    /**
     * 验证异步 common 接口调用可以通过本地 RPC 传输完成。
     */
    @Test
    void commonInterfaceShouldSupportAsyncReturn() {
        RpcCodecRegistry codecRegistry = codecRegistry();
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        new RpcServiceBinder(transport, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        PlayerRemoteRpc client = new RpcClientFactory(transport, codecRegistry).create(PlayerRemoteRpc.class);

        PlayerQueryResponseDTO response = client.queryPlayerAsync(new PlayerQueryRequestDTO(7L))
                .toCompletableFuture()
                .join()
                .orThrow();

        assertEquals("player-7", response.name);
    }

    /**
     * 验证业务失败说明不会在核心 RPC 链路中丢失。
     */
    @Test
    void commonInterfaceShouldKeepFailureMessage() {
        RpcCodecRegistry codecRegistry = codecRegistry();
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        new RpcServiceBinder(transport, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        PlayerRemoteRpc client = new RpcClientFactory(transport, codecRegistry).create(PlayerRemoteRpc.class);

        RpcResult<PlayerQueryResponseDTO> result = client.queryMissingPlayer(new PlayerQueryRequestDTO(404L));

        assertFalse(result.success());
        assertEquals(RpcErrorCode.INVALID_REQUEST.code(), result.errorCode().code());
        assertEquals("player not found: 404", result.errorMsg());
    }

    /**
     * 楠岃瘉 RPC client factory 浼氬鐢ㄧ浉鍚岄厤缃殑鎺ュ彛浠ｇ悊銆?     */
    @Test
    void clientFactoryShouldReuseProxyForSameInterfaceAndOptions() {
        RpcCodecRegistry codecRegistry = codecRegistry();
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        new RpcServiceBinder(transport, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        RpcCallOptions options = RpcCallOptions.defaults().withTraceId("trace-reuse");
        RpcClientFactory factory = new RpcClientFactory(transport, codecRegistry, options);

        PlayerRemoteRpc first = factory.create(PlayerRemoteRpc.class);
        PlayerRemoteRpc second = factory.create(PlayerRemoteRpc.class);
        PlayerRemoteRpc third = factory.create(
                PlayerRemoteRpc.class,
                options.withTraceId("trace-reuse-other"));

        assertSame(first, second);
        assertNotSame(first, third);
        assertEquals("player-9", first.queryPlayer(new PlayerQueryRequestDTO(9L)).orThrow().name);
    }

    /**
     * 验证复用同一个客户端代理时，RpcCallContext 可以按调用覆盖 traceId 和 replyTopic。
     */
    @Test
    void rpcCallContextShouldOverridePerInvocationWhileReusingProxy() {
        RpcCodecRegistry codecRegistry = codecRegistry();
        CapturingRpcTransport transport = new CapturingRpcTransport();
        new RpcServiceBinder(transport, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        RpcCallOptions options = RpcCallOptions.defaults()
                .withReplyTopic("reply-default")
                .withTraceId("trace-default");
        RpcClientFactory factory = new RpcClientFactory(transport, codecRegistry, options);
        PlayerRemoteRpc client = factory.create(PlayerRemoteRpc.class);

        RpcResult<PlayerQueryResponseDTO> first = RpcCallContext.with(
                RpcCallContext.empty().withReplyTopic("reply-a").withTraceId("trace-a"),
                () -> client.queryPlayer(new PlayerQueryRequestDTO(1L)));
        RpcResult<PlayerQueryResponseDTO> second = RpcCallContext.with(
                RpcCallContext.empty().withReplyTopic("reply-b").withTraceId("trace-b"),
                () -> client.queryPlayer(new PlayerQueryRequestDTO(2L)));

        assertSame(client, factory.create(PlayerRemoteRpc.class));
        assertEquals("trace-a", first.traceId());
        assertEquals("trace-b", second.traceId());
        assertEquals("reply-a", transport.requests().get(0).replyTopic());
        assertEquals("reply-b", transport.requests().get(1).replyTopic());
        assertEquals("trace-a", transport.requests().get(0).traceId());
        assertEquals("trace-b", transport.requests().get(1).traceId());
        assertNotEquals(first.correlationId(), second.correlationId());
    }

    /**
     * 验证默认 correlationId 生成器避免 UUID 热路径，并保持实例内唯一。
     */
    @Test
    void defaultCorrelationIdGeneratorShouldGenerateCompactUniqueIds() {
        DefaultRpcCorrelationIdGenerator generator = new DefaultRpcCorrelationIdGenerator("node A");
        Set<String> ids = new HashSet<>();

        for (int index = 0; index < 1000; index++) {
            String id = generator.nextCorrelationId();
            assertTrue(id.startsWith("node_A-"));
            assertFalse(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
            assertTrue(ids.add(id));
        }
    }

    private RpcCodecRegistry codecRegistry() {
        RpcCodecRegistry registry = new RpcCodecRegistry();
        registry.register(
                PlayerQueryRequestDTO.class,
                new ProtocolDefinition(1001, "player.query.request", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(PlayerQueryRequestCodec.INSTANCE));
        registry.register(
                PlayerQueryResponseDTO.class,
                new ProtocolDefinition(1002, "player.query.response", ProtocolDirection.SERVER_TO_CLIENT, 1),
                new GeneratedProtocolCodec<>(PlayerQueryResponseCodec.INSTANCE));
        return registry;
    }

    /**
     * 测试用远程玩家 RPC。
     *
     * @author zn
     */
    @RpcService(name = "player.remote", version = 1)
    interface PlayerRemoteRpc {

        /**
         * 查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1001, timeoutMillis = 3000)
        RpcResult<PlayerQueryResponseDTO> queryPlayer(PlayerQueryRequestDTO request);

        /**
         * 异步查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 异步结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1002, timeoutMillis = 3000)
        CompletionStage<RpcResult<PlayerQueryResponseDTO>> queryPlayerAsync(PlayerQueryRequestDTO request);

        /**
         * 查询不存在的玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 失败结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1003, timeoutMillis = 3000)
        RpcResult<PlayerQueryResponseDTO> queryMissingPlayer(PlayerQueryRequestDTO request);
    }

    /**
     * 测试用远程玩家 RPC 实现。
     *
     * @author zn
     */
    static final class PlayerRemoteRpcImpl implements PlayerRemoteRpc {

        /**
         * 查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<PlayerQueryResponseDTO> queryPlayer(final PlayerQueryRequestDTO request) {
            PlayerQueryResponseDTO response = new PlayerQueryResponseDTO(request.uid, "player-" + request.uid);
            return RpcResult.success(response);
        }

        /**
         * 异步查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 异步结果；不可为空；线程安全。
         */
        @Override
        public CompletionStage<RpcResult<PlayerQueryResponseDTO>> queryPlayerAsync(
                final PlayerQueryRequestDTO request) {
            return CompletableFuture.completedFuture(queryPlayer(request));
        }

        /**
         * 查询不存在的玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 失败结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<PlayerQueryResponseDTO> queryMissingPlayer(final PlayerQueryRequestDTO request) {
            return RpcResult.failure(RpcErrorCode.INVALID_REQUEST, "player not found: " + request.uid);
        }
    }

    /**
     * 测试用查询请求 DTO。
     *
     * @author zn
     */
    static final class PlayerQueryRequestDTO {

        /**
         * 玩家 ID。
         */
        private long uid;

        PlayerQueryRequestDTO() {
        }

        PlayerQueryRequestDTO(final long uid) {
            this.uid = uid;
        }
    }

    /**
     * 测试用查询响应 DTO。
     *
     * @author zn
     */
    static final class PlayerQueryResponseDTO {

        /**
         * 玩家 ID。
         */
        private long uid;

        /**
         * 玩家名。
         */
        private String name = "";

        PlayerQueryResponseDTO() {
        }

        PlayerQueryResponseDTO(final long uid, final String name) {
            this.uid = uid;
            this.name = name;
        }
    }

    /**
     * 测试用查询请求 codec。
     *
     * @author zn
     */
    static final class PlayerQueryRequestCodec implements ZeroPayloadCodec<PlayerQueryRequestDTO> {

        /**
         * 单例。
         */
        private static final PlayerQueryRequestCodec INSTANCE = new PlayerQueryRequestCodec();

        private PlayerQueryRequestCodec() {
        }

        /**
         * 返回 provider 名称。
         *
         * @return provider 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "PlayerQueryRequestCodec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<PlayerQueryRequestDTO> messageType() {
            return PlayerQueryRequestDTO.class;
        }

        /**
         * 写入请求。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerQueryRequestDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid);
            writer.endObject(marker);
        }

        /**
         * 读取请求。
         *
         * @param reader 读取器；不可为空。
         * @return 请求；不可为空；线程不安全。
         */
        @Override
        public PlayerQueryRequestDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            PlayerQueryRequestDTO message = new PlayerQueryRequestDTO();
            if (reader.hasRemainingInObject(end)) {
                message.uid = reader.readLong();
            }
            reader.endObject(end);
            return message;
        }
    }

    /**
     * 测试用查询响应 codec。
     *
     * @author zn
     */
    static final class PlayerQueryResponseCodec implements ZeroPayloadCodec<PlayerQueryResponseDTO> {

        /**
         * 单例。
         */
        private static final PlayerQueryResponseCodec INSTANCE = new PlayerQueryResponseCodec();

        private PlayerQueryResponseCodec() {
        }

        /**
         * 返回 provider 名称。
         *
         * @return provider 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "PlayerQueryResponseCodec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<PlayerQueryResponseDTO> messageType() {
            return PlayerQueryResponseDTO.class;
        }

        /**
         * 写入响应。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerQueryResponseDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid);
            writer.writeString(message.name);
            writer.endObject(marker);
        }

        /**
         * 读取响应。
         *
         * @param reader 读取器；不可为空。
         * @return 响应；不可为空；线程不安全。
         */
        @Override
        public PlayerQueryResponseDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            PlayerQueryResponseDTO message = new PlayerQueryResponseDTO();
            if (reader.hasRemainingInObject(end)) {
                message.uid = reader.readLong();
            }
            if (reader.hasRemainingInObject(end)) {
                message.name = reader.readString();
            }
            reader.endObject(end);
            return message;
        }
    }

    /**
     * 测试用可捕获请求的 RPC 传输。
     *
     * @author zn
     */
    static final class CapturingRpcTransport implements RpcTransport, RpcHandlerRegistry {

        /**
         * 内存 RPC 传输。
         */
        private final InMemoryRpcTransport delegate = new InMemoryRpcTransport();

        /**
         * 已捕获请求列表。
         */
        private final CopyOnWriteArrayList<RpcRequest> requests = new CopyOnWriteArrayList<>();

        /**
         * 返回传输名称。
         *
         * @return 传输名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "capture";
        }

        /**
         * 发送 request/response 请求。
         *
         * @param request RPC 请求；不可为空。
         * @return 响应阶段；不可为空；线程安全。
         */
        @Override
        public CompletionStage<RpcResponse> request(final RpcRequest request) {
            requests.add(request);
            return delegate.request(request);
        }

        /**
         * 发送 oneway 请求。
         *
         * @param request RPC 请求；不可为空。
         * @return 发送阶段；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> oneway(final RpcRequest request) {
            requests.add(request);
            return delegate.oneway(request);
        }

        /**
         * 注册 RPC 请求处理器。
         *
         * @param serviceName 路由服务名；不可为空。
         * @param methodName 路由方法名；不可为空。
         * @param handler 处理器；不可为空。
         */
        @Override
        public void register(final String serviceName, final String methodName, final RpcHandler handler) {
            delegate.register(serviceName, methodName, handler);
        }

        /**
         * 取消注册 RPC 请求处理器。
         *
         * @param serviceName 路由服务名；不可为空。
         * @param methodName 路由方法名；不可为空。
         */
        @Override
        public void unregister(final String serviceName, final String methodName) {
            delegate.unregister(serviceName, methodName);
        }

        /**
         * 返回已捕获请求列表。
         *
         * @return 已捕获请求列表；可变、有序、可能为空、线程安全。
         */
        CopyOnWriteArrayList<RpcRequest> requests() {
            return requests;
        }
    }
}
