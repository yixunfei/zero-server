package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import group.zn.zero.starter.ZeroRuntimeComponents;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroRuntimeFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * production starter 工厂测试。
 *
 * @author zn
 */
class ZeroProductionRuntimeFactoryTest {

    /**
     * 验证非法 profile 使用固定安全消息，且完整异常图不包含调用方原值。
     */
    @Test
    void unsupportedProfileShouldNotLeakRawValue() {
        String secretSentinel = "PAF1-UNSUPPORTED-PROFILE-SECRET";

        ZeroException failure = assertThrows(
                ZeroException.class,
                () -> ZeroProductionRuntimeFactory.builder(
                        secretSentinel,
                        new MapZeroConfig(Map.of()),
                        record -> { },
                        ZeroRuntimeExecutors.direct()));

        assertSame(SystemErrorCode.INVALID_ARGUMENT, failure.errorCode());
        assertEquals("unsupported production runtime profile", failure.message());
        assertNull(failure.getCause());
        assertThrowableGraphRedacted(failure, secretSentinel);
    }

    /**
     * 验证 production diagnose、runtime report 及失败异常图均不会保留配置中的 runtime name，
     * 顶层与嵌套 starter report 使用同一固定脱敏值。
     */
    @Test
    void productionReportsAndFailuresShouldRedactConfiguredRuntimeName() {
        String secretSentinel = "PAF1-RUNTIME-NAME-SECRET-SENTINEL";
        MapZeroConfig namedConfig = new MapZeroConfig(Map.of(
                ZeroRuntimeConfigKeys.ZERO_NAME,
                secretSentinel));

        ZeroProductionAssemblyReport diagnosis = isolatedProductionBuilder(namedConfig).diagnose();
        assertEquals(ZeroProductionAssemblyReport.REDACTED_RUNTIME_NAME, diagnosis.name());
        assertNull(diagnosis.runtimeReport());
        assertFalse(diagnosis.toString().contains(secretSentinel));

        ZeroProductionRuntime runtime = isolatedProductionBuilder(namedConfig).build();
        try {
            ZeroProductionAssemblyReport report = runtime.report();
            assertEquals(ZeroProductionAssemblyReport.REDACTED_RUNTIME_NAME, report.name());
            assertEquals(
                    ZeroProductionAssemblyReport.REDACTED_RUNTIME_NAME,
                    report.runtimeReport().name());
            assertFalse(report.toString().contains(secretSentinel));
            assertFalse(report.runtimeReport().toString().contains(secretSentinel));
        } finally {
            runtime.close();
        }

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedProductionBuilder(new MapZeroConfig(Map.of(
                                ZeroRuntimeConfigKeys.ZERO_NAME, secretSentinel,
                                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true")))
                        .build());
        assertThrowableGraphRedacted(failure, secretSentinel);
    }

    /**
     * 验证 production Builder 公共 API 不再暴露跳过 startup health 的兼容入口。
     */
    @Test
    void productionBuilderShouldNotExposeStartupHealthBypass() {
        boolean bypassExposed = Arrays.stream(ZeroProductionRuntimeBuilder.class.getMethods())
                .anyMatch(method -> "healthChecks".equals(method.getName()));

        assertFalse(bypassExposed);
    }

    /**
     * 验证本地默认工厂不会因为配置里出现 Adapter 键而创建真实 Adapter。
     */
    @Test
    void localDefaultShouldIgnoreProductionAdapterKeys() {
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localDefault(new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true",
                MongoDriverSettings.PROPERTY_MONGO_URI, "mongodb://user:secret@127.0.0.1:27017",
                MongoDriverSettings.PROPERTY_MONGO_DATABASE, "zero_secret")));

        assertInstanceOf(InMemoryRpcTransport.class, components.rpcTransport());
        assertFalse(components.assemblyReport().componentTypes().toString().contains(MongoDataAdapter.class.getName()));
    }

    /**
     * 验证 production 缺少已启用 Adapter 的必填配置时 fail-fast。
     */
    @Test
    void productionBuilderShouldFailFastWhenRequiredConfigMissing() {
        String secretSentinel = "redis-cache-secret-sentinel";
        ProductionAdapterException exception = assertThrows(
                ProductionAdapterException.class,
                () -> ZeroProductionRuntimeFactory
                        .productionBuilder(new MapZeroConfig(Map.of(
                                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                                RedisDriverSettings.PROPERTY_REDIS_URI,
                                "redis://:" + secretSentinel + "@127.0.0.1:6379/0")))
                        .build());

        assertSame(ProductionAdapterErrorCode.CONFIG_MISSING, exception.errorCode());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, exception.failurePhase());
        assertEquals("production-runtime", exception.adapterName());
        assertTrue(exception.message().contains(
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE));
        assertTrue(exception.message().contains(
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME));
        assertTrue(exception.message().contains("builder.redisCacheValueCodec"));
        assertNull(exception.getCause());
        assertThrowableGraphRedacted(exception, secretSentinel);
    }

    /**
     * 验证只读诊断报告可以列出缺失键且不创建真实 Adapter。
     */
    @Test
    void diagnoseShouldExposeMissingKeysWithoutCreatingAdapters() {
        String secretSentinel = "diagnose-secret-sentinel";
        ZeroProductionAssemblyReport report = ZeroProductionRuntimeFactory.diagnoseProduction(new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                RedisDriverSettings.PROPERTY_REDIS_URI,
                "redis://:" + secretSentinel + "@127.0.0.1:6379/0")));

        ZeroProductionAdapterStatus status = report.adapterStatus(ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE)
                .orElseThrow();
        assertEquals(ZeroProductionAdapterState.MISSING_CONFIG, status.state());
        assertEquals(
                java.util.List.of(
                        "builder.redisCacheValueCodec",
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE),
                status.missingConfigKeys());
        assertTrue(status.configuredConfigKeys().contains(RedisDriverSettings.PROPERTY_REDIS_URI));
        assertEquals(ProductionAdapterFailurePhase.NONE, status.failurePhase());
        assertNull(status.errorCode());
        assertFalse(report.containsFragment(secretSentinel));
        assertFalse(report.containsFragment("redis://"));
    }

    /**
     * 验证 production Adapter selector 只接受精确小写布尔值。
     */
    @Test
    void productionBuilderShouldRejectNonCanonicalAdapterSelector() {
        Map<String, String> selectors = Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED,
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED,
                ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED,
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED,
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED,
                ZeroProductionRuntimeBuilder.ADAPTER_POSTGRESQL_DATA);

        for (Map.Entry<String, String> selector : selectors.entrySet()) {
            ProductionAdapterException exception = assertThrows(
                    ProductionAdapterException.class,
                    () -> ZeroProductionRuntimeFactory.productionBuilder(new MapZeroConfig(Map.of(
                                    selector.getKey(),
                                    "TRUE")))
                            .build(),
                    selector.getKey());

            assertSame(ProductionAdapterErrorCode.CONFIG_INVALID, exception.errorCode(), selector.getKey());
            assertEquals(
                    ProductionAdapterFailurePhase.CONFIG_SELECTION,
                    exception.failurePhase(),
                    selector.getKey());
            assertEquals(selector.getValue(), exception.adapterName(), selector.getKey());
            assertTrue(exception.message().contains(selector.getKey()), selector.getKey());
            assertFalse(exception.message().contains("TRUE"), selector.getKey());
        }
    }

    /**
     * 验证全部 Adapter disabled 与缺失 discovery mode 是合法 production 组合，并拒绝非规范 Nacos mode。
     */
    @Test
    void productionDefaultsShouldStayExternalFreeAndNacosModeShouldBeStrict() {
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.productionBuilder(
                        new MapZeroConfig(Map.of(
                                ZeroRuntimeConfigKeys.ZERO_MODE,
                                ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION)))
                .build();
        try {
            assertTrue(runtime.kafkaRpcAdapter().isEmpty());
            assertTrue(runtime.mongoDataAdapter().isEmpty());
            assertTrue(runtime.redisDataAdapter().isEmpty());
            assertTrue(runtime.postgresqlDataAdapter().isEmpty());
            assertTrue(runtime.serviceDiscovery().isEmpty());
            assertEquals(
                    ZeroProductionAdapterState.DISABLED,
                    runtime.report()
                            .adapterStatus(ZeroProductionRuntimeBuilder.ADAPTER_NACOS_DISCOVERY)
                            .orElseThrow()
                            .state());
        } finally {
            runtime.close();
        }

        ProductionAdapterException exception = assertThrows(
                ProductionAdapterException.class,
                () -> ZeroProductionRuntimeFactory.productionBuilder(new MapZeroConfig(Map.of(
                                NacosDiscoveryConfigKeys.DISCOVERY_MODE,
                                "NACOS")))
                        .build());
        assertSame(ProductionAdapterErrorCode.CONFIG_INVALID, exception.errorCode());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_SELECTION, exception.failurePhase());
        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_NACOS_DISCOVERY, exception.adapterName());
        assertFalse(exception.message().contains("NACOS"));
    }

    /**
     * 验证同时启用全部 Adapter 时，每项 production 隔离配置都会成为显式必填键。
     */
    @Test
    void allEnabledAdaptersShouldExposeEveryMissingIsolationKey() {
        ProductionAdapterException exception = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedProductionBuilder(new MapZeroConfig(Map.of(
                                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true",
                                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true",
                                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true",
                                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED, "true",
                                NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS)))
                        .build());

        assertSame(ProductionAdapterErrorCode.CONFIG_MISSING, exception.errorCode());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, exception.failurePhase());
        List.of(
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC,
                        MongoDriverSettings.PROPERTY_MONGO_URI,
                        MongoDriverSettings.PROPERTY_MONGO_DATABASE,
                        RedisDriverSettings.PROPERTY_REDIS_URI,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                        "builder.redisCacheValueCodec",
                        PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                        PostgresqlDriverSettings.USERNAME_PROPERTY,
                        PostgresqlDriverSettings.PASSWORD_PROPERTY,
                        PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
                        NacosDiscoveryConfigKeys.SERVER_ADDR,
                        NacosDiscoveryConfigKeys.NAMESPACE,
                        NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                        NacosDiscoveryConfigKeys.DEFAULT_CLUSTER)
                .forEach(key -> assertTrue(exception.message().contains(key), key));
    }

    /**
     * 验证 production Kafka 不接受已废弃 bootstrap 属性键，也不会泄露旧键中的连接值。
     */
    @Test
    void productionKafkaShouldRejectLegacyBootstrapProperty() {
        String secretSentinel = "legacy-secret-broker:9092";
        ProductionAdapterException exception = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedProductionBuilder(new MapZeroConfig(Map.ofEntries(
                                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true"),
                                Map.entry("zero.kafka.bootstrapServers", secretSentinel),
                                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, "client"),
                                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, "group"),
                                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, "topic"),
                                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, "reply"))))
                        .build());

        assertSame(ProductionAdapterErrorCode.CONFIG_MISSING, exception.errorCode());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, exception.failurePhase());
        assertTrue(exception.message().contains(
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS));
        assertFalse(exception.message().contains("zero.kafka.bootstrapServers"));
        assertThrowableGraphRedacted(exception, secretSentinel);
    }

    /**
     * 验证诊断报告只输出键名和来源，不泄露连接串、密码或 token。
     */
    @Test
    void productionReportShouldRedactSensitiveValues() {
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.productionBuilder(new MapZeroConfig(Map.ofEntries(
                        Map.entry(ZeroRuntimeConfigKeys.ZERO_MODE, ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION),
                        Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS, "secret-broker:9092"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, "secret-kafka-client"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, "secret-kafka-group"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, "secret.kafka.topic"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, "secret-kafka-reply"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true"),
                        Map.entry(MongoDriverSettings.PROPERTY_MONGO_URI,
                                "mongodb://user:secret-pass@127.0.0.1:27017"),
                        Map.entry(MongoDriverSettings.PROPERTY_MONGO_DATABASE, "zero_secret_database"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true"),
                        Map.entry(RedisDriverSettings.PROPERTY_REDIS_URI, "redis://:secret-pass@127.0.0.1:6379/0"),
                        Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED, "true"),
                        Map.entry(PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                                "jdbc:postgresql://127.0.0.1:5432/secret_db"),
                        Map.entry(PostgresqlDriverSettings.USERNAME_PROPERTY, "secret_user"),
                        Map.entry(PostgresqlDriverSettings.PASSWORD_PROPERTY, "secret-pass"),
                        Map.entry(PostgresqlDriverSettings.TABLE_NAME_PROPERTY, "secret_table"))))
                .build();
        try {
            ZeroProductionAssemblyReport report = runtime.report();
            String text = report.toString();

            assertTrue(runtime.kafkaRpcAdapter().isPresent());
            assertTrue(runtime.mongoDataAdapter().isPresent());
            assertTrue(runtime.redisDataAdapter().isPresent());
            assertTrue(runtime.postgresqlDataAdapter().isPresent());
            assertEquals(ZeroProductionAdapterState.CREATED,
                    report.adapterStatus(ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA).orElseThrow().state());
            assertFalse(text.contains("secret-pass"));
            assertFalse(text.contains("secret-broker"));
            assertFalse(text.contains("mongodb://"));
            assertFalse(text.contains("redis://"));
            assertFalse(text.contains("jdbc:postgresql"));
            assertFalse(text.contains("secret_user"));
            assertFalse(text.contains("zero_secret_database"));
            assertFalse(text.contains("secret-kafka-client"));
            assertFalse(text.contains("secret-kafka-group"));
            assertFalse(text.contains("secret.kafka.topic"));
            assertFalse(text.contains("secret-kafka-reply"));
            assertFalse(text.contains("secret_table"));
        } finally {
            runtime.close();
        }
    }

    /**
     * 验证 Redis cache 显式提供 value codec 后会替换 runtime cache slot。
     */
    @Test
    void redisCacheOptInShouldRequireAndUseValueCodec() {
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.externalTestBuilder(new MapZeroConfig(Map.of(
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                        RedisDriverSettings.PROPERTY_REDIS_URI, "redis://127.0.0.1:6379/0",
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, "factory-test",
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "runtime")))
                .redisCacheValueCodec(StringObjectCacheValueCodec.INSTANCE)
                .build();
        try {
            assertTrue(runtime.redisClient().isPresent());
            assertInstanceOf(RedisDistributedCacheService.class, runtime.components().cacheService());
            assertSame(runtime.components().cacheService(), runtime.components().cacheService());
        } finally {
            runtime.close();
        }
    }

    /**
     * 验证启用 Nacos discovery 时 production runtime 会暴露 RPC resolver 协作入口。
     */
    @Test
    void nacosOptInShouldExposeRpcServiceResolver() {
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.externalTestBuilder(new MapZeroConfig(Map.of(
                        NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS,
                        NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                        NacosDiscoveryConfigKeys.NAMESPACE, "factory-test",
                        NacosDiscoveryConfigKeys.DEFAULT_GROUP, "FACTORY_TEST_GROUP",
                        NacosDiscoveryConfigKeys.DEFAULT_CLUSTER, "FACTORY_TEST_CLUSTER")))
                .build();
        try {
            assertTrue(runtime.serviceDiscovery().isPresent());
            assertTrue(runtime.rpcServiceResolver().isPresent());
        } finally {
            runtime.close();
        }
    }

    /**
     * 创建不读取宿主 system property 或 environment 的 production builder。
     *
     * <p>该 fixture 用于证明 production 新配置键自身必填，并防止开发机或 external-test profile 注入的
     * Adapter 环境变量改变缺配置测试结论。方法不启动 runtime、不创建外部资源。
     *
     * @param config 显式测试配置；不可为空。
     * @return 已隔离宿主配置来源的 builder；不可为空，非线程安全。
     */
    private static ZeroProductionRuntimeBuilder isolatedProductionBuilder(final MapZeroConfig config) {
        return ZeroProductionRuntimeFactory.productionBuilder(config)
                .configSourceLookups(key -> null, key -> null);
    }

    private static void assertThrowableGraphRedacted(
            final Throwable failure,
            final String secretSentinel) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(secretSentinel));
        assertFalse(failure.toString().contains(secretSentinel));
    }

    /**
     * 测试用字符串 value codec。
     *
     * @author zn
     */
    private static final class StringObjectCacheValueCodec implements CacheValueCodec<Object> {

        /**
         * 单例。
         */
        private static final StringObjectCacheValueCodec INSTANCE = new StringObjectCacheValueCodec();

        /**
         * 阻止外部创建测试 codec。
         */
        private StringObjectCacheValueCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空，线程安全。
         */
        @Override
        public String name() {
            return "test-string-object-cache-value";
        }

        /**
         * 编码字符串值。
         *
         * @param value 缓存值；不可为空。
         * @return payload；不可为空，线程安全。
         */
        @Override
        public byte[] encode(final Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 解码字符串值。
         *
         * @param bytes payload；不可为空。
         * @return 字符串值；不可为空，线程安全。
         */
        @Override
        public Object decode(final byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
