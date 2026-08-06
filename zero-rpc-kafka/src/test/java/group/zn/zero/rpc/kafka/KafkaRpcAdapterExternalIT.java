package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcCallMode;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.rpc.server.RpcServiceBinder;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;

/**
 * Kafka RPC adapter 真实 broker 外部集成测试。
 *
 * @author zn
 */
class KafkaRpcAdapterExternalIT {

    /**
     * external-tests profile 标记。
     */
    private static final String EXTERNAL_TESTS_ENABLED = "zero.external.tests";

    /**
     * Kafka broker 地址系统属性。
     */
    private static final String KAFKA_BOOTSTRAP_PROPERTY = "zero.kafka.bootstrapServers";

    /**
     * Kafka broker 地址环境变量。
     */
    private static final String KAFKA_BOOTSTRAP_ENV = "ZERO_KAFKA_BOOTSTRAP_SERVERS";

    /**
     * 等待异步消费完成的超时时间。
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 验证真实 Kafka broker 上 request/response 与 oneway RPC 都能完成。
     *
     * @throws Exception 当等待 oneway 消费完成时抛出。
     */
    @Test
    void kafkaBrokerShouldRunRequestResponseAndOnewayRpc() throws Exception {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must be enabled");
        String bootstrapServers = kafkaBootstrapServers();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RpcCodecRegistry codecRegistry = codecRegistry();
        AtomicInteger touched = new AtomicInteger();
        KafkaRpcAdapter provider = null;
        KafkaRpcAdapter caller = null;
        try {
            provider = new KafkaRpcAdapter(settings(
                    bootstrapServers,
                    "provider-" + suffix,
                    "provider-group-" + suffix,
                    "zero.rpc.external." + suffix,
                    "reply-provider-" + suffix));
            caller = new KafkaRpcAdapter(settings(
                    bootstrapServers,
                    "caller-" + suffix,
                    "caller-group-" + suffix,
                    "zero.rpc.external." + suffix,
                    "reply-caller-" + suffix));
            new RpcServiceBinder(provider, codecRegistry).bind(ExternalPlayerRpc.class, new ExternalPlayerRpcImpl(touched));
            ExternalPlayerRpc client = new RpcClientFactory(
                    caller,
                    codecRegistry,
                    RpcCallOptions.defaults()
                            .withReplyTopic("reply-caller-" + suffix)
                            .withTraceId("trace-kafka-external"))
                    .create(ExternalPlayerRpc.class);

            LoginResponseDTO response = client.login(new LoginRequestDTO(10086L, "zone-a")).orThrow();
            RpcResult<Void> touchResult = client.touch(new TouchRequestDTO(10086L));
            await(() -> touched.get() == 10086);

            assertEquals(10086L, response.uid);
            assertEquals("player-10086", response.name);
            assertEquals("zone-a", response.zone);
            assertTrue(touchResult.success());
        } finally {
            if (caller != null) {
                caller.close();
            }
            if (provider != null) {
                provider.close();
            }
        }
    }

    private String kafkaBootstrapServers() {
        String servers = Optional.ofNullable(System.getProperty(KAFKA_BOOTSTRAP_PROPERTY))
                .or(() -> Optional.ofNullable(System.getenv(KAFKA_BOOTSTRAP_ENV)))
                .filter(value -> !value.isBlank())
                .orElse(null);
        assertNotNull(servers, "Kafka bootstrap servers must be set by "
                + KAFKA_BOOTSTRAP_PROPERTY + " or " + KAFKA_BOOTSTRAP_ENV);
        return servers;
    }

    private KafkaRpcSettings settings(
            final String bootstrapServers,
            final String clientId,
            final String consumerGroupId,
            final String topicPrefix,
            final String replyTopic) {
        return new KafkaRpcSettings(
                bootstrapServers,
                "zero-rpc-external-" + clientId,
                consumerGroupId,
                topicPrefix,
                replyTopic,
                128,
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                Map.of(),
                Map.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    private RpcCodecRegistry codecRegistry() {
        RpcCodecRegistry registry = new RpcCodecRegistry();
        registry.register(
                LoginRequestDTO.class,
                new ProtocolDefinition(9101, "external.login.request", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(LoginRequestCodec.INSTANCE));
        registry.register(
                LoginResponseDTO.class,
                new ProtocolDefinition(9102, "external.login.response", ProtocolDirection.SERVER_TO_CLIENT, 1),
                new GeneratedProtocolCodec<>(LoginResponseCodec.INSTANCE));
        registry.register(
                TouchRequestDTO.class,
                new ProtocolDefinition(9103, "external.touch.request", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(TouchRequestCodec.INSTANCE));
        return registry;
    }

    private void await(final BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100L);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + WAIT_TIMEOUT);
    }

    /**
     * 测试用玩家 RPC。
     *
     * @author zn
     */
    @RpcService(name = "external.player", version = 1)
    interface ExternalPlayerRpc {

        /**
         * 登录玩家。
         *
         * @param request 登录请求；不可为空。
         * @return 登录结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1001, timeoutMillis = 10000, partitionKey = "playerId")
        RpcResult<LoginResponseDTO> login(LoginRequestDTO request);

        /**
         * 触碰玩家。
         *
         * @param request 触碰请求；不可为空。
         * @return 空结果；不可为空；线程安全。
         */
        @RpcMethod(id = 1002, mode = RpcCallMode.ONEWAY, timeoutMillis = 10000)
        RpcResult<Void> touch(TouchRequestDTO request);
    }

    /**
     * 测试用玩家 RPC 实现。
     *
     * @author zn
     */
    static final class ExternalPlayerRpcImpl implements ExternalPlayerRpc {

        /**
         * oneway 触碰计数器。
         */
        private final AtomicInteger touched;

        /**
         * 创建测试 RPC 实现。
         *
         * @param touched oneway 触碰计数器；不可为空。
         */
        ExternalPlayerRpcImpl(final AtomicInteger touched) {
            this.touched = touched;
        }

        /**
         * 登录玩家。
         *
         * @param request 登录请求；不可为空。
         * @return 登录结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<LoginResponseDTO> login(final LoginRequestDTO request) {
            return RpcResult.success(new LoginResponseDTO(request.uid, "player-" + request.uid, request.zone));
        }

        /**
         * 触碰玩家。
         *
         * @param request 触碰请求；不可为空。
         * @return 空结果；不可为空；线程安全。
         */
        @Override
        public RpcResult<Void> touch(final TouchRequestDTO request) {
            touched.set((int) request.uid);
            return RpcResult.success(null);
        }
    }

    /**
     * 登录请求 DTO。
     *
     * @author zn
     */
    static final class LoginRequestDTO {

        /**
         * 玩家 ID。
         */
        private long uid;

        /**
         * 分区名称。
         */
        private String zone = "";

        LoginRequestDTO() {
        }

        LoginRequestDTO(final long uid, final String zone) {
            this.uid = uid;
            this.zone = zone;
        }

        /**
         * 返回分区键。
         *
         * @return 玩家 ID。
         */
        long playerId() {
            return uid;
        }
    }

    /**
     * 触碰请求 DTO。
     *
     * @author zn
     */
    static final class TouchRequestDTO {

        /**
         * 玩家 ID。
         */
        private long uid;

        TouchRequestDTO() {
        }

        TouchRequestDTO(final long uid) {
            this.uid = uid;
        }
    }

    /**
     * 登录响应 DTO。
     *
     * @author zn
     */
    static final class LoginResponseDTO {

        /**
         * 玩家 ID。
         */
        private long uid;

        /**
         * 玩家名。
         */
        private String name = "";

        /**
         * 分区名称。
         */
        private String zone = "";

        LoginResponseDTO() {
        }

        LoginResponseDTO(final long uid, final String name, final String zone) {
            this.uid = uid;
            this.name = name;
            this.zone = zone;
        }
    }

    /**
     * 登录请求 codec。
     *
     * @author zn
     */
    static final class LoginRequestCodec implements ZeroPayloadCodec<LoginRequestDTO> {

        /**
         * 单例。
         */
        private static final LoginRequestCodec INSTANCE = new LoginRequestCodec();

        private LoginRequestCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "external-login-request";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<LoginRequestDTO> messageType() {
            return LoginRequestDTO.class;
        }

        /**
         * 写入请求。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final LoginRequestDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid);
            writer.writeString(message.zone);
            writer.endObject(marker);
        }

        /**
         * 读取请求。
         *
         * @param reader 读取器；不可为空。
         * @return 请求；不可为空；线程不安全。
         */
        @Override
        public LoginRequestDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            LoginRequestDTO message = new LoginRequestDTO();
            if (reader.hasRemainingInObject(end)) {
                message.uid = reader.readLong();
            }
            if (reader.hasRemainingInObject(end)) {
                message.zone = reader.readString();
            }
            reader.endObject(end);
            return message;
        }
    }

    /**
     * 触碰请求 codec。
     *
     * @author zn
     */
    static final class TouchRequestCodec implements ZeroPayloadCodec<TouchRequestDTO> {

        /**
         * 单例。
         */
        private static final TouchRequestCodec INSTANCE = new TouchRequestCodec();

        private TouchRequestCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "external-touch-request";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<TouchRequestDTO> messageType() {
            return TouchRequestDTO.class;
        }

        /**
         * 写入请求。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final TouchRequestDTO message) {
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
        public TouchRequestDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            TouchRequestDTO message = new TouchRequestDTO();
            if (reader.hasRemainingInObject(end)) {
                message.uid = reader.readLong();
            }
            reader.endObject(end);
            return message;
        }
    }

    /**
     * 登录响应 codec。
     *
     * @author zn
     */
    static final class LoginResponseCodec implements ZeroPayloadCodec<LoginResponseDTO> {

        /**
         * 单例。
         */
        private static final LoginResponseCodec INSTANCE = new LoginResponseCodec();

        private LoginResponseCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "external-login-response";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<LoginResponseDTO> messageType() {
            return LoginResponseDTO.class;
        }

        /**
         * 写入响应。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final LoginResponseDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid);
            writer.writeString(message.name);
            writer.writeString(message.zone);
            writer.endObject(marker);
        }

        /**
         * 读取响应。
         *
         * @param reader 读取器；不可为空。
         * @return 响应；不可为空；线程不安全。
         */
        @Override
        public LoginResponseDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            LoginResponseDTO message = new LoginResponseDTO();
            if (reader.hasRemainingInObject(end)) {
                message.uid = reader.readLong();
            }
            if (reader.hasRemainingInObject(end)) {
                message.name = reader.readString();
            }
            if (reader.hasRemainingInObject(end)) {
                message.zone = reader.readString();
            }
            reader.endObject(end);
            return message;
        }
    }
}
