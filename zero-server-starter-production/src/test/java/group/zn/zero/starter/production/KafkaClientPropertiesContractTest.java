package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;

/**
 * Production Kafka 公共客户端属性白名单与三路传播契约测试。
 *
 * @author zn
 */
class KafkaClientPropertiesContractTest {

    /** 用于反证非法属性值不会进入异常图的敏感哨兵。 */
    private static final String SECRET = "PAF1-KAFKA-CLIENT-PROPERTY-SECRET";

    /**
     * 验证白名单拒绝所有不属于精确 {@code security.protocol}、{@code ssl.*} 或 {@code sasl.*} 的键。
     */
    @Test
    void clientPropertyWhitelistShouldRejectFrameworkManagedAndMalformedKeys() {
        List<String> rejectedKeys = List.of(
                "bootstrap.servers",
                "client.id",
                "group.id",
                "key.serializer",
                "value.deserializer",
                "request.timeout.ms",
                "default.api.timeout.ms",
                "security.protocol.extra",
                "Security.Protocol",
                "ssl.",
                "sasl.",
                " ssl.truststore.location",
                "schema.registry.url",
                "");

        for (String rejectedKey : rejectedKeys) {
            ProductionAdapterException failure = assertThrows(
                    ProductionAdapterException.class,
                    () -> isolatedBuilder(Map.of())
                            .kafkaClientProperties(Map.of(rejectedKey, SECRET)),
                    rejectedKey);

            assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC, failure.adapterName());
            assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
            assertSame(ProductionAdapterErrorCode.CONFIG_INVALID, failure.errorCode());
            assertEquals(
                    "invalid production adapter config key: builder.kafkaClientProperties",
                    failure.message());
            assertNull(failure.getCause());
            assertFalse(stackTrace(failure).contains(SECRET), rejectedKey);
        }
    }

    /**
     * 验证允许的公共安全属性以 defensive copy 的相同值进入 producer、consumer 与实际 Admin health 属性，
     * 调用方后续篡改或清空原 Map 不影响 Builder 快照。
     */
    @Test
    void allowedClientPropertiesShouldReachProducerConsumerAndAdminHealthUnchanged() {
        Map<String, Object> expected = Map.of(
                "security.protocol", "SASL_SSL",
                "ssl.truststore.location", SECRET + "-truststore",
                "sasl.mechanism", "PLAIN");
        Map<String, Object> supplied = new LinkedHashMap<>(expected);
        Map<String, String> config = Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS, "127.0.0.1:9092",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, "property-test-client",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, "property-test-group",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, "property.test",
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, "property.test.reply");
        ZeroProductionRuntimeBuilder builder = isolatedBuilder(config)
                .kafkaClientProperties(supplied);
        supplied.put("security.protocol", SECRET + "-mutated");
        supplied.remove("ssl.truststore.location");
        ZeroProductionRuntime runtime = builder.build();
        supplied.clear();
        try {
            KafkaRpcLifecycleAdapter adapter = assertInstanceOf(
                    KafkaRpcLifecycleAdapter.class,
                    runtime.require(ProductionRuntimeCapabilities.RPC_TRANSPORT));
            KafkaRpcSettings settings = adapter.settings();
            KafkaClusterHealthCheck healthCheck = new KafkaClusterHealthCheck(settings);
            Properties adminProperties = healthCheck.adminProperties(Duration.ofSeconds(2));

            for (Map.Entry<String, Object> entry : expected.entrySet()) {
                assertEquals(entry.getValue(), settings.producerProperties().get(entry.getKey()), entry.getKey());
                assertEquals(entry.getValue(), settings.consumerProperties().get(entry.getKey()), entry.getKey());
                assertEquals(entry.getValue(), adminProperties.get(entry.getKey()), entry.getKey());
            }
            assertEquals("2000", adminProperties.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG));
            assertEquals("2000", adminProperties.getProperty(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG));
            assertFalse(settings.toString().contains(SECRET));
            assertFalse(runtime.productionReport().toString().contains(SECRET));
        } finally {
            runtime.close();
        }
    }

    /**
     * 创建不读取宿主进程配置来源的 production builder。
     *
     * @param config 显式测试配置；不可为空，调用后会复制。
     * @return 隔离宿主配置来源的 builder；不可为空，非线程安全。
     */
    private ZeroProductionRuntimeBuilder isolatedBuilder(final Map<String, String> config) {
        return ZeroProductionRuntimeFactory.productionBuilder(new MapZeroConfig(config))
                .configSourceLookups(key -> null, key -> null);
    }

    /**
     * 把完整异常图打印为文本，供敏感哨兵反证。
     *
     * @param failure 待打印异常；不可为空。
     * @return 完整堆栈文本；不可为空，调用方可变，方法不修改异常图。
     */
    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }
}
