package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.remote.ActorDispatchOptions;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import group.zn.zero.discovery.nacos.NacosDiscoverySettings;
import group.zn.zero.discovery.nacos.NacosHealthUpdateMode;
import group.zn.zero.discovery.ServiceDiscoveryConstants;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.game.GameActorGateway;
import group.zn.zero.game.GameRequestContext;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.rpc.actor.RpcActorMessageCodec;
import group.zn.zero.rpc.actor.RpcActorRouteResolver;
import group.zn.zero.rpc.actor.RpcRemoteActorGateway;
import group.zn.zero.rpc.actor.RpcRemoteActorReceiver;
import group.zn.zero.rpc.actor.RpcRemoteActorRegistration;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.discovery.NacosRpcMetadataMapper;
import group.zn.zero.rpc.discovery.ServiceDiscoveryRpcServiceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;

/**
 * Kafka + Nacos 远程 Actor gateway 多 JVM 外部集成测试。
 *
 * @author zn
 */
public class KafkaNacosRemoteActorGatewayExternalIT {

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
     * Nacos 地址系统属性。
     */
    private static final String NACOS_SERVER_ADDR_PROPERTY = "zero.nacos.serverAddr";

    /**
     * Nacos 地址环境变量。
     */
    private static final String NACOS_SERVER_ADDR_ENV = "ZERO_NACOS_SERVER_ADDR";

    /**
     * 等待超时时间。
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(40);

    /**
     * provider 进程准备完成文件。
     */
    private static final String READY_FILE = "provider.ready";

    /**
     * provider 进程停止文件。
     */
    private static final String STOP_FILE = "provider.stop";

    /**
     * provider 处理结果文件。
     */
    private static final String HANDLED_FILE = "actor.handled";

    /**
     * 验证 caller JVM 可以通过 Nacos 解析 route，并经 Kafka 投递远程 Actor 消息到 provider JVM。
     *
     * @throws Exception 当进程、文件或远程投递失败时抛出。
     */
    @Test
    void kafkaAndNacosShouldDeliverRemoteActorMessageAcrossJvm() throws Exception {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must be enabled");
        String kafkaBootstrap = kafkaBootstrapServers();
        String nacosServerAddr = nacosServerAddr();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path workDir = Files.createTempDirectory("zero-s4b04-actor-");
        Process provider = startProviderProcess(kafkaBootstrap, nacosServerAddr, suffix, workDir);
        try {
            await(() -> Files.exists(workDir.resolve(READY_FILE)));
            callRemoteActor(kafkaBootstrap, nacosServerAddr, suffix, workDir);
            await(() -> Files.exists(workDir.resolve(HANDLED_FILE)));
            assertEquals(
                    "10086:touch-" + suffix + ":trace-s4b04-" + suffix,
                    Files.readString(workDir.resolve(HANDLED_FILE)));
        } finally {
            Files.writeString(workDir.resolve(STOP_FILE), "stop");
            if (!provider.waitFor(20, TimeUnit.SECONDS)) {
                provider.destroyForcibly();
            }
            assertEquals(0, provider.exitValue(), providerOutput(workDir));
        }
    }

    /**
     * provider JVM 入口。
     *
     * @param args 启动参数。
     * @throws Exception 当 provider 启动或运行失败时抛出。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length > 0 && "provider".equals(args[0])) {
            runProvider(args[1], args[2], args[3], Path.of(args[4]));
        }
    }

    private void callRemoteActor(
            final String kafkaBootstrap,
            final String nacosServerAddr,
            final String suffix,
            final Path workDir) throws Exception {
        String serviceName = serviceName(suffix);
        String replyTopic = "reply-caller-" + suffix;
        KafkaRpcAdapter caller = null;
        NacosDiscoveryAdapter discovery = null;
        try {
            caller = new KafkaRpcAdapter(settings(
                    kafkaBootstrap,
                    "actor-caller-" + suffix,
                    "actor-caller-group-" + suffix,
                    topicPrefix(suffix),
                    replyTopic));
            discovery = nacos(nacosServerAddr);
            discovery.start();
            ServiceDiscoveryRpcServiceResolver serviceResolver = new ServiceDiscoveryRpcServiceResolver(discovery);
            await(() -> {
                try {
                    serviceResolver.resolve(ServiceDiscoveryRpcServiceResolver.kafkaQuery(serviceName, 1));
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            });
            RpcCodecRegistry codecRegistry = codecRegistry();
            GameActorGateway gateway = new GameActorGateway(
                    new LocalActorScheduler(),
                    new RpcActorRouteResolver(
                            serviceResolver,
                            ignored -> ServiceDiscoveryRpcServiceResolver.kafkaQuery(serviceName, 1),
                            RpcRemoteActorGateway.DEFAULT_METHOD_NAME),
                    new RpcRemoteActorGateway(caller, new RpcActorMessageCodec(codecRegistry)),
                    ActorDispatchOptions.defaults().withReplyTopic(replyTopic));
            gateway.dispatch(
                            GameRequestContext.client("trace-s4b04-" + suffix),
                            LaneKey.scene("scene-" + suffix),
                            new RemoteActorTouchCommand(10086L, "touch-" + suffix))
                    .toCompletableFuture()
                    .get();
        } finally {
            if (discovery != null && discovery.running()) {
                discovery.stop();
            }
            if (caller != null) {
                caller.close();
            }
            if (!Files.exists(workDir.resolve(STOP_FILE))) {
                Files.writeString(workDir.resolve("caller.done"), "done");
            }
        }
    }

    private static void runProvider(
            final String kafkaBootstrap,
            final String nacosServerAddr,
            final String suffix,
            final Path workDir) throws Exception {
        Files.createDirectories(workDir);
        String serviceName = serviceName(suffix);
        String instanceId = "actor-provider-" + suffix;
        String consumerGroup = "actor-provider-group-" + suffix;
        String requestTopic = new KafkaRpcTopicResolver(topicPrefix(suffix)).requestTopic(serviceName);
        KafkaRpcAdapter provider = null;
        NacosDiscoveryAdapter discovery = null;
        RpcRemoteActorRegistration registration = null;
        try {
            RpcCodecRegistry codecRegistry = codecRegistry();
            RpcActorMessageCodec messageCodec = new RpcActorMessageCodec(codecRegistry);
            LocalActorScheduler scheduler = new LocalActorScheduler();
            scheduler.register(RemoteActorTouchCommand.class, ActorHandler.sync((context, message) -> {
                RemoteActorTouchCommand command = (RemoteActorTouchCommand) message.payload();
                writeHandled(workDir, command.uid() + ":" + command.marker() + ":" + context.traceId());
            }));
            provider = new KafkaRpcAdapter(settings(
                    kafkaBootstrap,
                    "actor-provider-" + suffix,
                    consumerGroup,
                    topicPrefix(suffix),
                    "reply-provider-" + suffix));
            registration = new RpcRemoteActorReceiver(provider, scheduler, messageCodec)
                    .register(serviceName, RpcRemoteActorGateway.DEFAULT_METHOD_NAME, requestTopic, consumerGroup);
            discovery = nacos(nacosServerAddr);
            discovery.start();
            discovery.register(new ServiceInstance(
                    serviceName,
                    instanceId,
                    "127.0.0.1",
                    6200,
                    ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                    ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                    true,
                    true,
                    true,
                    1.0D,
                    1.0D,
                    NacosRpcMetadataMapper.rpcProviderMetadata(
                            serviceName,
                            1,
                            "kafka",
                            requestTopic,
                            consumerGroup,
                            instanceId,
                            "1",
                            "external-test",
                            Map.of("zero.actor.gateway", "s4b04"))));
            Files.writeString(workDir.resolve(READY_FILE), "ready");
            waitForStop(workDir.resolve(STOP_FILE));
        } finally {
            if (discovery != null && discovery.running()) {
                discovery.unregister(serviceName, ServiceDiscoveryConstants.DEFAULT_GROUP_NAME, instanceId);
                discovery.stop();
            }
            if (registration != null) {
                registration.close();
            }
            if (provider != null) {
                provider.close();
            }
        }
    }

    private static RpcCodecRegistry codecRegistry() {
        RpcCodecRegistry registry = new RpcCodecRegistry();
        registry.register(
                RemoteActorTouchCommand.class,
                new ProtocolDefinition(9301, "external.actor.touch", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(RemoteActorTouchCommandCodec.INSTANCE));
        return registry;
    }

    private static KafkaRpcSettings settings(
            final String bootstrapServers,
            final String clientId,
            final String consumerGroupId,
            final String topicPrefix,
            final String replyTopic) {
        return new KafkaRpcSettings(
                bootstrapServers,
                "zero-rpc-actor-" + clientId,
                consumerGroupId,
                topicPrefix,
                replyTopic,
                128,
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                Map.of(),
                Map.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    private static NacosDiscoveryAdapter nacos(final String serverAddr) {
        return new NacosDiscoveryAdapter(new NacosDiscoverySettings(
                serverAddr,
                NacosDiscoverySettings.DEFAULT_NAMESPACE,
                "",
                "",
                "",
                "",
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                5_000,
                false,
                NacosHealthUpdateMode.REREGISTER,
                Map.of()));
    }

    private Process startProviderProcess(
            final String kafkaBootstrap,
            final String nacosServerAddr,
            final String suffix,
            final Path workDir) throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                KafkaNacosRemoteActorGatewayExternalIT.class.getName(),
                "provider",
                kafkaBootstrap,
                nacosServerAddr,
                suffix,
                workDir.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDir.resolve("provider.log").toFile());
        return builder.start();
    }

    private static String serviceName(final String suffix) {
        return "zero.s4b04.actor." + suffix;
    }

    private static String topicPrefix(final String suffix) {
        return "zero.rpc.actor.external." + suffix;
    }

    private String kafkaBootstrapServers() {
        return Optional.ofNullable(System.getProperty(KAFKA_BOOTSTRAP_PROPERTY))
                .or(() -> Optional.ofNullable(System.getenv(KAFKA_BOOTSTRAP_ENV)))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AssertionError("Kafka bootstrap servers must be set"));
    }

    private String nacosServerAddr() {
        return Optional.ofNullable(System.getProperty(NACOS_SERVER_ADDR_PROPERTY))
                .or(() -> Optional.ofNullable(System.getenv(NACOS_SERVER_ADDR_ENV)))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AssertionError("Nacos server address must be set"));
    }

    private static void writeHandled(final Path workDir, final String value) {
        try {
            Files.writeString(workDir.resolve(HANDLED_FILE), value);
        } catch (IOException ex) {
            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "write actor handled marker failed", ex);
        }
    }

    private static void waitForStop(final Path stopFile) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && !Files.exists(stopFile)) {
            TimeUnit.MILLISECONDS.sleep(100L);
        }
    }

    private void await(final BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200L);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + WAIT_TIMEOUT);
    }

    private String providerOutput(final Path workDir) throws IOException {
        Path log = workDir.resolve("provider.log");
        return Files.exists(log)
                ? new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8)
                : "provider log not found";
    }

    /**
     * 远程 Actor 测试命令。
     *
     * @param uid 玩家 ID。
     * @param marker 标记。
     * @author zn
     */
    public record RemoteActorTouchCommand(long uid, String marker) {
    }

    /**
     * 远程 Actor 测试命令 codec。
     *
     * @author zn
     */
    public static final class RemoteActorTouchCommandCodec implements ZeroPayloadCodec<RemoteActorTouchCommand> {

        /**
         * 单例。
         */
        private static final RemoteActorTouchCommandCodec INSTANCE = new RemoteActorTouchCommandCodec();

        private RemoteActorTouchCommandCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "external-actor-touch-command";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<RemoteActorTouchCommand> messageType() {
            return RemoteActorTouchCommand.class;
        }

        /**
         * 写入命令。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final RemoteActorTouchCommand message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid());
            writer.writeString(message.marker());
            writer.endObject(marker);
        }

        /**
         * 读取命令。
         *
         * @param reader 读取器；不可为空。
         * @return 命令；不可为空；线程安全。
         */
        @Override
        public RemoteActorTouchCommand read(final ZeroReader reader) {
            int end = reader.beginObject();
            long uid = 0L;
            String marker = "";
            if (reader.hasRemainingInObject(end)) {
                uid = reader.readLong();
            }
            if (reader.hasRemainingInObject(end)) {
                marker = reader.readString();
            }
            reader.endObject(end);
            return new RemoteActorTouchCommand(uid, marker);
        }
    }
}
