package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcCallMode;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportSnapshot;
import group.zn.zero.rpc.server.RpcServiceBinder;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.security.SecurityMetadataSnapshot;
import group.zn.zero.security.SecurityMetadataVerifier;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Kafka RPC 适配器测试。
 *
 * @author zn
 */
class KafkaRpcAdapterTest {

    /** Verifies tampered metadata and replayed assertions are rejected before handler execution. */
    @Test
    void tamperedAndReplayedMetadataAreRejected() {
        InMemoryKafkaRpcMessageGateway gateway = new InMemoryKafkaRpcMessageGateway();
        KafkaRpcAdapter provider = new KafkaRpcAdapter(settings("provider", "reply-provider"), gateway);
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller"), gateway);
        SecurityMetadataAssertion assertion = SecurityMetadataAssertion.digest("test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Set<String> consumed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        provider.securityMetadataVerifier((snapshot, receivedAt) -> {
            if (snapshot == null || !assertion.verify(snapshot) || !consumed.add(snapshot.assertionReference())) {
                return CompletableFuture.completedFuture(null);
            }
            return SecurityMetadataAssertion.verifier(assertion).verify(snapshot, receivedAt);
        });
        AtomicInteger handled = new AtomicInteger();
        provider.register("svc", "method", current -> {
            handled.incrementAndGet();
            return CompletableFuture.completedFuture(new RpcResponse(current.correlationId(), current.traceId(),
                    SystemErrorCode.OK, "ok", new byte[0]));
        });
        SecurityMetadataSnapshot signed = SecurityMetadataAssertion.signed(context(), "assertion-replay", assertion);
        KafkaRpcEnvelopeCodec codec = new KafkaRpcEnvelopeCodec();
        gateway.emitRequest(codec.encodeRequest(requestWith(signed, "corr-1")));
        assertEquals(1, handled.get());

        gateway.emitRequest(codec.encodeRequest(requestWith(signed, "corr-2")));
        assertEquals(1, handled.get(), "replayed assertion must not reach handler");

        SecurityMetadataSnapshot tampered = new SecurityMetadataSnapshot(signed.subject(), signed.transport(),
                signed.peerAddress(), signed.trustedSourceAddress(), signed.traceId(), signed.correlationId(),
                java.util.Set.of("rpc.invoke", "admin"), signed.assertionReference(), signed.issuedAt(),
                signed.expiresAt(), signed.signature());
        gateway.emitRequest(codec.encodeRequest(requestWith(tampered, "corr-3")));
        assertEquals(1, handled.get(), "tampered signature must not reach handler");
        caller.close(); provider.close();
    }

    private static SecurityContext context() {
        Instant issued = Instant.now();
        return new SecurityContext("subject", issued, issued.plusSeconds(30), "kafka", "peer", "trusted",
                "trace-security", "corr-security", java.util.Set.of("rpc.invoke"), Map.of());
    }

    private static RpcRequest requestWith(final SecurityMetadataSnapshot metadata, final String correlationId) {
        return new RpcRequest(correlationId, "reply-caller", "svc", "method", "trace-security",
                Instant.now().plusSeconds(30), RpcMode.REQUEST_RESPONSE, "", "", "", metadata, new byte[0]);
    }


    @Test
    void securityMetadataIsRevalidatedBeforeHandler() {
        InMemoryKafkaRpcMessageGateway gateway = new InMemoryKafkaRpcMessageGateway();
        KafkaRpcAdapter provider = new KafkaRpcAdapter(settings("provider", "reply-provider"), gateway);
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller"), gateway);
        AtomicInteger verified = new AtomicInteger();
        provider.securityMetadataVerifier(SecurityMetadataVerifier.synchronous(snapshot -> {
            verified.incrementAndGet();
            return new SecurityContext(snapshot.subject(), snapshot.issuedAt(), snapshot.expiresAt(), "kafka",
                    snapshot.peerAddress(), snapshot.trustedSourceAddress(), snapshot.traceId(), snapshot.correlationId(),
                    snapshot.permissions(), Map.of());
        }));
        RpcRequest request = new RpcRequest("corr-security", "reply-caller", "svc", "method", "trace-security",
                Instant.now().plusSeconds(30), RpcMode.REQUEST_RESPONSE, "", "", "", metadata(), new byte[0]);
        AtomicInteger handled = new AtomicInteger();
        provider.register("svc", "method", current -> {
            assertTrue(group.zn.zero.security.SecurityContextBridge.current().isPresent());
            handled.incrementAndGet();
            return CompletableFuture.completedFuture(new RpcResponse(current.correlationId(), current.traceId(),
                    SystemErrorCode.OK, "ok", new byte[0]));
        });
        gateway.emitRequest(new KafkaRpcEnvelopeCodec().encodeRequest(request));
        assertEquals(1, verified.get());
        assertEquals(1, handled.get());
        caller.close(); provider.close();
    }

    private static SecurityMetadataSnapshot metadata() {
        Instant issued = Instant.now();
        return new SecurityMetadataSnapshot("subject", "tcp", "peer", "trusted", "trace-security",
                "corr-security", java.util.Set.of("network.request"), "assertion-ref", issued, issued.plusSeconds(30), "test-signature");
    }

    @Test
    void commonInterfaceShouldInvokeThroughKafkaAdapter() {
        InMemoryKafkaRpcMessageGateway gateway = new InMemoryKafkaRpcMessageGateway();
        RpcCodecRegistry codecRegistry = codecRegistry();
        KafkaRpcAdapter provider = new KafkaRpcAdapter(settings("provider", "reply-provider"), gateway);
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller"), gateway);
        new RpcServiceBinder(provider, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        PlayerRemoteRpc client = new RpcClientFactory(
                caller,
                codecRegistry,
                RpcCallOptions.defaults()
                        .withReplyTopic("reply-caller")
                        .withTraceId("trace-kafka"))
                .create(PlayerRemoteRpc.class);

        PlayerQueryResponseDTO response = client.queryPlayer(new PlayerQueryRequestDTO(10086L)).orThrow();

        assertEquals(10086L, response.uid);
        assertEquals("player-10086", response.name);
        assertEquals("player.rpc.custom", gateway.firstTopic());
        assertEquals("10086", gateway.firstKey());
        assertEquals("player-provider-group", gateway.firstGroup());
        caller.close();
        provider.close();
    }

    /**
     * 验证 oneway 方法只等待 Kafka send 完成，不等待业务响应。
     */
    @Test
    void commonInterfaceShouldSupportKafkaOneway() {
        InMemoryKafkaRpcMessageGateway gateway = new InMemoryKafkaRpcMessageGateway();
        RpcCodecRegistry codecRegistry = codecRegistry();
        KafkaRpcAdapter provider = new KafkaRpcAdapter(settings("provider", "reply-provider"), gateway);
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller"), gateway);
        AtomicInteger touched = new AtomicInteger();
        new RpcServiceBinder(provider, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcOnewayImpl(touched));
        PlayerRemoteRpc client = new RpcClientFactory(
                caller,
                codecRegistry,
                RpcCallOptions.defaults().withReplyTopic("reply-caller"))
                .create(PlayerRemoteRpc.class);

        RpcResult<Void> result = client.touchPlayer(new PlayerTouchRequestDTO(7L));

        assertTrue(result.success());
        assertEquals(7, touched.get());
        caller.close();
        provider.close();
    }

    /**
     * 验证 observer 能记录 Kafka RPC 的 request、handler、response 和 pending 信号。
     */
    @Test
    void observerShouldRecordRequestResponseSignalsAndSnapshot() {
        InMemoryKafkaRpcMessageGateway gateway = new InMemoryKafkaRpcMessageGateway();
        RecordingRpcObserver observer = new RecordingRpcObserver();
        RpcCodecRegistry codecRegistry = codecRegistry();
        KafkaRpcAdapter provider = new KafkaRpcAdapter(settings("provider", "reply-provider"), gateway, observer);
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller"), gateway, observer);
        new RpcServiceBinder(provider, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
        PlayerRemoteRpc client = new RpcClientFactory(
                caller,
                codecRegistry,
                RpcCallOptions.defaults()
                        .withReplyTopic("reply-caller")
                        .withTraceId("trace-observed"))
                .create(PlayerRemoteRpc.class);

        PlayerQueryResponseDTO response = client.queryPlayer(new PlayerQueryRequestDTO(42L)).orThrow();

        RpcTransportSnapshot callerSnapshot = caller.snapshot();
        RpcTransportSnapshot providerSnapshot = provider.snapshot();
        assertEquals(42L, response.uid);
        assertTrue(observer.contains(RpcTransportEventType.PENDING_REGISTERED));
        assertTrue(observer.contains(RpcTransportEventType.REQUEST_SENT));
        assertTrue(observer.contains(RpcTransportEventType.REQUEST_RECEIVED));
        assertTrue(observer.contains(RpcTransportEventType.HANDLER_STARTED));
        assertTrue(observer.contains(RpcTransportEventType.HANDLER_SUCCEEDED));
        assertTrue(observer.contains(RpcTransportEventType.RESPONSE_SENT));
        assertTrue(observer.contains(RpcTransportEventType.PENDING_COMPLETED));
        assertEquals(1L, callerSnapshot.requestSentCount());
        assertEquals(1L, providerSnapshot.requestReceivedCount());
        assertEquals(1L, providerSnapshot.responseSentCount());
        assertEquals("1", callerSnapshot.attributes().get("pendingCompletedCount"));
        caller.close();
        provider.close();
    }

    /**
     * 验证消费端收到过期请求后拒绝执行业务 handler，并返回超时错误响应。
     */
    @Test
    void kafkaConsumerShouldRejectExpiredRequest() {
        InMemoryKafkaRpcMessageGateway gateway = new InMemoryKafkaRpcMessageGateway();
        RecordingRpcObserver observer = new RecordingRpcObserver();
        RpcCodecRegistry codecRegistry = codecRegistry();
        KafkaRpcSettings settings = settings("provider", "reply-provider");
        KafkaRpcAdapter provider = new KafkaRpcAdapter(settings, gateway, observer);
        AtomicInteger invoked = new AtomicInteger();
        new RpcServiceBinder(provider, codecRegistry).bind(PlayerRemoteRpc.class, new PlayerRemoteRpcOnewayImpl(invoked));
        KafkaRpcEnvelopeCodec envelopeCodec = new KafkaRpcEnvelopeCodec();
        KafkaRpcTopicResolver topicResolver = new KafkaRpcTopicResolver(settings.topicPrefix());
        CopyOnWriteArrayList<RpcResponse> responses = new CopyOnWriteArrayList<>();
        gateway.subscribe("reply-expired", message ->
                responses.add(envelopeCodec.decode(message.value()).response()));
        RpcRequest expired = new RpcRequest(
                "corr-expired",
                "reply-expired",
                "player.remote:v1",
                "1001",
                "trace-expired",
                Instant.now().minusMillis(1),
                RpcMode.REQUEST_RESPONSE,
                "player.rpc.custom",
                "",
                "",
                envelopeCodec.encodeRequest(new RpcRequest(
                        "payload-marker",
                        "reply-expired",
                        "noop",
                        "noop",
                        "trace-expired",
                        Instant.now().plusSeconds(1),
                        RpcMode.ONEWAY,
                        new byte[0])));

        gateway.send(new KafkaRpcMessage(
                topicResolver.requestTopic(expired),
                expired.correlationId(),
                envelopeCodec.encodeRequest(expired))).toCompletableFuture().join();

        assertEquals(0, invoked.get());
        assertEquals(1, responses.size());
        assertEquals(RpcErrorCode.REQUEST_TIMEOUT.code(), responses.getFirst().errorCode().code());
        assertTrue(observer.contains(RpcTransportEventType.REQUEST_REJECTED));
        assertFalse(observer.contains(RpcTransportEventType.HANDLER_STARTED));
        provider.close();
    }

    /**
     * 验证 Kafka gateway 不可用时 request 快速失败，不无限排队。
     */
    @Test
    void kafkaGatewayUnavailableShouldFailRequestFast() {
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller"),
                new FailingKafkaRpcMessageGateway());
        RpcRequest request = new RpcRequest(
                "corr-fail",
                "reply-caller",
                "player.remote:v1",
                "1001",
                "trace-fail",
                Instant.now().plusSeconds(1),
                RpcMode.REQUEST_RESPONSE,
                new byte[0]);

        CompletionException exception = assertThrows(CompletionException.class, () ->
                caller.request(request).toCompletableFuture().join());

        ZeroException cause = assertInstanceOf(ZeroException.class, exception.getCause());
        assertEquals(RpcErrorCode.TRANSPORT_UNAVAILABLE.code(), cause.errorCode().code());
        caller.close();
    }

    /**
     * 验证 pending 容量控制是严格的。
     */
    @Test
    void pendingRequestsShouldRespectCapacityStrictly() {
        HoldingKafkaRpcMessageGateway gateway = new HoldingKafkaRpcMessageGateway();
        KafkaRpcAdapter caller = new KafkaRpcAdapter(settings("caller", "reply-caller", 1), gateway);
        RpcRequest first = new RpcRequest(
                "corr-one",
                "reply-caller",
                "player.remote:v1",
                "1001",
                "trace-one",
                Instant.now().plusSeconds(3),
                RpcMode.REQUEST_RESPONSE,
                new byte[0]);
        RpcRequest second = new RpcRequest(
                "corr-two",
                "reply-caller",
                "player.remote:v1",
                "1001",
                "trace-two",
                Instant.now().plusSeconds(3),
                RpcMode.REQUEST_RESPONSE,
                new byte[0]);

        CompletionStage<RpcResponse> firstStage = caller.request(first);
        CompletionException exception = assertThrows(CompletionException.class, () ->
                caller.request(second).toCompletableFuture().join());

        ZeroException cause = assertInstanceOf(ZeroException.class, exception.getCause());
        assertEquals(RpcErrorCode.TRANSPORT_UNAVAILABLE.code(), cause.errorCode().code());
        assertFalse(firstStage.toCompletableFuture().isDone());
        assertEquals(1L, caller.snapshot().pendingRejectedCount());
        caller.close();
    }

    /**
     * 验证监听器异常不会让内存 gateway 后续监听器失效。
     */
    @Test
    void listenerFailureShouldNotBlockFollowingKafkaMessages() {
        ResilientInMemoryKafkaRpcMessageGateway gateway = new ResilientInMemoryKafkaRpcMessageGateway();
        AtomicInteger delivered = new AtomicInteger();
        gateway.subscribe("topic", message -> {
            throw new IllegalStateException("decode failed");
        });
        gateway.subscribe("topic", message -> delivered.incrementAndGet());

        gateway.send(new KafkaRpcMessage("topic", "key", new byte[0])).toCompletableFuture().join();

        assertEquals(1, gateway.isolatedFailures());
        assertEquals(1, delivered.get());
    }

    /**
     * 验证 Kafka envelope 解码会拒绝超大字段长度。
     */
    @Test
    void envelopeCodecShouldRejectOversizedFieldBeforeAllocation() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x5A524B31);
        out.writeByte(1);
        out.writeByte(1);
        out.writeInt(1024 * 1024 + 1);
        out.flush();

        ZeroException exception = assertThrows(ZeroException.class, () ->
                new KafkaRpcEnvelopeCodec().decode(bytes.toByteArray()));

        assertEquals(RpcErrorCode.CODEC_FAILED.code(), exception.errorCode().code());
    }

    private KafkaRpcSettings settings(final String clientId, final String replyTopic) {
        return settings(clientId, replyTopic, 64);
    }

    private KafkaRpcSettings settings(final String clientId, final String replyTopic, final int pendingCapacity) {
        return new KafkaRpcSettings(
                "memory",
                clientId,
                "player-service",
                "zero.rpc",
                replyTopic,
                pendingCapacity,
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                Map.of(),
                Map.of());
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
        registry.register(
                PlayerTouchRequestDTO.class,
                new ProtocolDefinition(1003, "player.touch.request", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(PlayerTouchRequestCodec.INSTANCE));
        return registry;
    }

    /**
     * 测试用远程玩家 RPC。
     *
     * @author zn
     */
    @RpcService(name = "player.remote", version = 1, topic = "player.rpc.custom", group = "player-provider-group")
    interface PlayerRemoteRpc {

        /**
         * 查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 查询结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1001, timeoutMillis = 3000, partitionKey = "playerId")
        RpcResult<PlayerQueryResponseDTO> queryPlayer(PlayerQueryRequestDTO request);

        /**
         * 触碰玩家。
         *
         * @param request 触碰请求；不可为空。
         * @return RPC 空结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1002, mode = RpcCallMode.ONEWAY, timeoutMillis = 3000)
        RpcResult<Void> touchPlayer(PlayerTouchRequestDTO request);
    }

    /**
     * 测试用玩家 RPC 实现。
     *
     * @author zn
     */
    static final class PlayerRemoteRpcImpl implements PlayerRemoteRpc {

        /**
         * 查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 查询结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<PlayerQueryResponseDTO> queryPlayer(final PlayerQueryRequestDTO request) {
            return RpcResult.success(new PlayerQueryResponseDTO(request.uid, "player-" + request.uid));
        }

        /**
         * 触碰玩家。
         *
         * @param request 触碰请求；不可为空。
         * @return RPC 空结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<Void> touchPlayer(final PlayerTouchRequestDTO request) {
            return RpcResult.success(null);
        }
    }

    /**
     * 测试用 oneway 玩家 RPC 实现。
     *
     * @author zn
     */
    static final class PlayerRemoteRpcOnewayImpl implements PlayerRemoteRpc {

        /**
         * 触碰计数器。
         */
        private final AtomicInteger touched;

        /**
         * 创建 oneway 玩家 RPC 实现。
         *
         * @param touched 触碰计数器；不可为空。
         */
        PlayerRemoteRpcOnewayImpl(final AtomicInteger touched) {
            this.touched = touched;
        }

        /**
         * 查询玩家。
         *
         * @param request 查询请求；不可为空。
         * @return RPC 查询结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<PlayerQueryResponseDTO> queryPlayer(final PlayerQueryRequestDTO request) {
            return RpcResult.failure(SystemErrorCode.NOT_IMPLEMENTED, "not used");
        }

        /**
         * 触碰玩家。
         *
         * @param request 触碰请求；不可为空。
         * @return RPC 空结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<Void> touchPlayer(final PlayerTouchRequestDTO request) {
            touched.set((int) request.uid);
            return RpcResult.success(null);
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

        /**
         * 返回玩家 ID。
         *
         * @return 玩家 ID。
         */
        long playerId() {
            return uid;
        }
    }

    /**
     * 测试用触碰请求 DTO。
     *
     * @author zn
     */
    static final class PlayerTouchRequestDTO {

        /**
         * 玩家 ID。
         */
        private long uid;

        PlayerTouchRequestDTO() {
        }

        PlayerTouchRequestDTO(final long uid) {
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
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空；线程安全。
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
     * 测试用触碰请求 codec。
     *
     * @author zn
     */
    static final class PlayerTouchRequestCodec implements ZeroPayloadCodec<PlayerTouchRequestDTO> {

        /**
         * 单例。
         */
        private static final PlayerTouchRequestCodec INSTANCE = new PlayerTouchRequestCodec();

        private PlayerTouchRequestCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "PlayerTouchRequestCodec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<PlayerTouchRequestDTO> messageType() {
            return PlayerTouchRequestDTO.class;
        }

        /**
         * 写入请求。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerTouchRequestDTO message) {
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
        public PlayerTouchRequestDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            PlayerTouchRequestDTO message = new PlayerTouchRequestDTO();
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
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空；线程安全。
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
     * 测试用内存 Kafka RPC 消息网关。
     *
     * @author zn
     */
    static class InMemoryKafkaRpcMessageGateway implements KafkaRpcMessageGateway {

        /**
         * topic 到监听器列表的映射。
         */
        private final ConcurrentHashMap<String, CopyOnWriteArrayList<KafkaRpcMessageListener>> listeners =
                new ConcurrentHashMap<>();

        /**
         * 第一次发送 topic。
         */
        private volatile String firstTopic = "";

        /**
         * 第一次发送 key。
         */
        private volatile String firstKey = "";

        /**
         * 第一次显式订阅 group。
         */
        private volatile String firstGroup = "";

        /**
         * 发送消息。
         *
         * @param message Kafka RPC 消息；不可为空。
         * @return 发送完成阶段；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> send(final KafkaRpcMessage message) {
            recordFirst(message);
            listeners.getOrDefault(message.topic(), new CopyOnWriteArrayList<>())
                    .forEach(listener -> listener.onMessage(message));
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 订阅 topic。
         *
         * @param topic Kafka topic；不可为空。
         * @param listener 消息监听器；不可为空。
         */
        @Override
        public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
            listeners.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        }

        /**
         * 按 group 订阅 topic。
         *
         * @param topic Kafka topic；不可为空。
         * @param group Kafka consumer group；不可为空。
         * @param listener 消息监听器；不可为空。
         */
        @Override
        public void subscribe(final String topic, final String group, final KafkaRpcMessageListener listener) {
            if (firstGroup.isBlank()) {
                firstGroup = group;
            }
            subscribe(topic, listener);
        }

        /**
         * 取消订阅 topic。
         *
         * @param topic Kafka topic；不可为空。
         * @param listener 消息监听器；不可为空。
         */
        @Override
        public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
            CopyOnWriteArrayList<KafkaRpcMessageListener> current = listeners.get(topic);
            if (current != null) {
                current.remove(listener);
            }
        }

        /**
         * 关闭网关。
         */
        @Override
        public void close() {
            listeners.clear();
        }

        void emitRequest(final byte[] encoded) {
            listeners.values().stream().flatMap(java.util.Collection::stream)
                    .forEach(listener -> listener.onMessage(new KafkaRpcMessage("request-topic", "security", encoded)));
        }

        /**
         * @return key；不可为空；线程安全。
         */
        String firstKey() {
            return firstKey;
        }

        /**
         * 返回第一次发送 topic。
         *
         * @return topic；不可为空；线程安全。
         */
        String firstTopic() {
            return firstTopic;
        }

        /**
         * 返回第一次显式订阅 group。
         *
         * @return group；不可为空；线程安全。
         */
        String firstGroup() {
            return firstGroup;
        }

        /**
         * 返回监听器表。
         *
         * @return 监听器表；可变、无序、可能为空、线程安全。
         */
        ConcurrentHashMap<String, CopyOnWriteArrayList<KafkaRpcMessageListener>> listeners() {
            return listeners;
        }

        /**
         * 记录第一次发送消息。
         *
         * @param message Kafka RPC 消息；不可为空。
         */
        void recordFirst(final KafkaRpcMessage message) {
            if (firstTopic.isBlank()) {
                firstTopic = message.topic();
                firstKey = message.key();
            }
        }
    }

    /**
     * 测试用失败 Kafka RPC 消息网关。
     *
     * @author zn
     */
    static final class FailingKafkaRpcMessageGateway implements KafkaRpcMessageGateway {

        /**
         * 发送消息。
         *
         * @param message Kafka RPC 消息；不可为空。
         * @return 失败完成阶段；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> send(final KafkaRpcMessage message) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(ZeroException.of(
                    RpcErrorCode.TRANSPORT_UNAVAILABLE,
                    "kafka gateway unavailable",
                    null));
            return future;
        }

        /**
         * 订阅 topic。
         *
         * @param topic Kafka topic；不可为空。
         * @param listener 消息监听器；不可为空。
         */
        @Override
        public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
        }

        /**
         * 取消订阅 topic。
         *
         * @param topic Kafka topic；不可为空。
         * @param listener 消息监听器；不可为空。
         */
        @Override
        public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
        }

        /**
         * 关闭网关。
         */
        @Override
        public void close() {
        }
    }

    /**
     * 测试用持有请求的 Kafka RPC 消息网关。
     *
     * @author zn
     */
    static final class HoldingKafkaRpcMessageGateway extends InMemoryKafkaRpcMessageGateway {

        /**
         * 发送消息但不投递。
         *
         * @param message Kafka RPC 消息；不可为空。
         * @return 发送完成阶段；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> send(final KafkaRpcMessage message) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 测试用具备异常隔离的内存 Kafka RPC 消息网关。
     *
     * @author zn
     */
    static final class ResilientInMemoryKafkaRpcMessageGateway extends InMemoryKafkaRpcMessageGateway {

        /**
         * 被隔离的监听器异常数量。
         */
        private final AtomicInteger isolatedFailures = new AtomicInteger();

        /**
         * 发送消息并隔离单个监听器异常。
         *
         * @param message Kafka RPC 消息；不可为空。
         * @return 发送完成阶段；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> send(final KafkaRpcMessage message) {
            listeners().getOrDefault(message.topic(), new CopyOnWriteArrayList<>()).forEach(listener -> {
                try {
                    listener.onMessage(message);
                } catch (RuntimeException ex) {
                    isolatedFailures.incrementAndGet();
                }
            });
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 返回已隔离的监听器异常数量。
         *
         * @return 异常数量；线程安全。
         */
        int isolatedFailures() {
            return isolatedFailures.get();
        }
    }

    /**
     * 测试用 RPC 传输观测器。
     *
     * @author zn
     */
    static final class RecordingRpcObserver implements group.zn.zero.rpc.observer.RpcTransportObserver {

        /**
         * 已记录事件。
         */
        private final CopyOnWriteArrayList<RpcTransportEvent> events = new CopyOnWriteArrayList<>();

        /**
         * 记录观测事件。
         *
         * @param event 观测事件；不可为空。
         */
        @Override
        public void onEvent(final RpcTransportEvent event) {
            events.add(event);
        }

        /**
         * 判断是否包含指定事件类型。
         *
         * @param type 事件类型；不可为空。
         * @return true 表示已记录。
         */
        boolean contains(final RpcTransportEventType type) {
            return events.stream().anyMatch(event -> event.type() == type);
        }
    }
}
