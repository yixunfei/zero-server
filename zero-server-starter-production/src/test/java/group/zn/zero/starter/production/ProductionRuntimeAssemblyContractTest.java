package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Production runtime 强制健康检查与原生启动预算装配契约测试。
 *
 * @author zn
 */
class ProductionRuntimeAssemblyContractTest {

    /** 用于反证 resolve 阶段原始校验异常不会进入 production 异常图的敏感哨兵。 */
    private static final String SECRET = "PAF1-RESOLVE-CONFIG-SECRET-SENTINEL";

    /**
     * 验证 Nacos 未显式配置 request timeout 时被单 Adapter 预算钳制，显式超预算配置则安全 fail-fast。
     */
    @Test
    void nacosRequestTimeoutShouldClampToAdapterBudgetAndRejectExplicitOverflow() {
        ZeroProductionRuntime runtime = isolatedBuilder(nacosConfig(null)).build();
        try {
            NacosDiscoveryAdapter adapter = assertInstanceOf(
                    NacosDiscoveryAdapter.class,
                    runtime.serviceDiscovery().orElseThrow());
            assertEquals(1_250, adapter.settings().requestTimeoutMillis());
        } finally {
            runtime.close();
        }

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedBuilder(nacosConfig("2000")).diagnose());
        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_NACOS_DISCOVERY, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.CONFIG_INVALID, failure.errorCode());
        assertEquals(
                "invalid production adapter config key: "
                        + NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS,
                failure.message());
        assertNull(failure.getCause());
        assertFalse(stackTrace(failure).contains("2000"), stackTrace(failure));
    }

    /**
     * 验证六个 production Adapter 槽位同时启用时恰好装配六个 startup health lifecycle，
     * 每个 lifecycle 都绑定唯一且完整的稳定 Adapter 名称。
     */
    @Test
    void everyEnabledAdapterSlotShouldInstallOneMandatoryStartupHealthLifecycle() {
        ZeroProductionRuntime runtime = isolatedBuilder(new MapZeroConfig(allEnabledConfig()))
                .redisCacheValueCodec(StringObjectCacheValueCodec.INSTANCE)
                .build();
        try {
            List<AdapterHealthCheckLifecycle> healthChecks = runtime.components()
                    .lifecycleComponents()
                    .stream()
                    .filter(AdapterHealthCheckLifecycle.class::isInstance)
                    .map(AdapterHealthCheckLifecycle.class::cast)
                    .toList();
            Set<String> expectedAdapters = Set.of(
                    ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                    ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA,
                    ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA,
                    ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE,
                    ZeroProductionRuntimeBuilder.ADAPTER_POSTGRESQL_DATA,
                    ZeroProductionRuntimeBuilder.ADAPTER_NACOS_DISCOVERY);
            Set<String> actualAdapters = healthChecks.stream()
                    .map(AdapterHealthCheckLifecycle::adapterName)
                    .collect(Collectors.toUnmodifiableSet());

            assertEquals(6, healthChecks.size());
            assertEquals(expectedAdapters, actualAdapters);
        } finally {
            runtime.close();
        }
    }

    /**
     * 验证 PostgreSQL table 与 Nacos option 的原始 resolve 校验失败归为配置错误，
     * 不会误报 client creation，也不会保留原始值、cause 或 stack。
     */
    @Test
    void resolveValidationFallbackShouldUseSafeConfigAttribution() {
        List<MapZeroConfig> invalidConfigs = List.of(
                invalidPostgresqlConfig(),
                invalidNacosOptionConfig());

        for (MapZeroConfig config : invalidConfigs) {
            ProductionAdapterException diagnosisFailure = assertThrows(
                    ProductionAdapterException.class,
                    () -> isolatedBuilder(config).diagnose());
            ProductionAdapterException buildFailure = assertThrows(
                    ProductionAdapterException.class,
                    () -> isolatedBuilder(config).build());

            assertSafeConfigFailure(diagnosisFailure);
            assertSafeConfigFailure(buildFailure);
            assertEquals(diagnosisFailure.adapterName(), buildFailure.adapterName());
            assertEquals(diagnosisFailure.failurePhase(), buildFailure.failurePhase());
            assertSame(diagnosisFailure.errorCode(), buildFailure.errorCode());
        }
    }

    private void assertSafeConfigFailure(final ProductionAdapterException failure) {
        assertEquals("production-runtime", failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.CONFIG_INVALID, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.CONFIG_INVALID.message(), failure.message());
        assertNull(failure.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
    }

    /**
     * 创建只读取显式测试 Map、不读取宿主属性或环境变量的 Builder。
     *
     * @param config 显式测试配置；不可为空。
     * @return 隔离宿主配置源的 production Builder；不可为空，非线程安全。
     */
    private ZeroProductionRuntimeBuilder isolatedBuilder(final MapZeroConfig config) {
        return ZeroProductionRuntimeFactory.productionBuilder(config)
                .configSourceLookups(key -> null, key -> null);
    }

    /**
     * 创建启用 Nacos 且单 Adapter 预算为 1250ms 的配置。
     *
     * @param requestTimeout 显式 Nacos request timeout；为空表示缺失并使用预算钳制。
     * @return 配置快照；不可为空，调用方不可变，线程安全。
     */
    private MapZeroConfig nacosConfig(final String requestTimeout) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_BUDGET_MILLIS, "5000");
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS, "1250");
        values.put(NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS);
        values.put(NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848");
        values.put(NacosDiscoveryConfigKeys.NAMESPACE, "assembly-contract");
        values.put(NacosDiscoveryConfigKeys.DEFAULT_GROUP, "ASSEMBLY_CONTRACT");
        values.put(NacosDiscoveryConfigKeys.DEFAULT_CLUSTER, "ASSEMBLY_CONTRACT");
        if (requestTimeout != null) {
            values.put(NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS, requestTimeout);
        }
        return new MapZeroConfig(values);
    }

    /**
     * 创建 table identifier 含敏感非法值的 PostgreSQL 配置。
     *
     * @return 不可变测试配置；不可为空，线程安全。
     */
    private MapZeroConfig invalidPostgresqlConfig() {
        return new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED, "true",
                PostgresqlDriverSettings.JDBC_URL_PROPERTY, "jdbc:postgresql://127.0.0.1:5432/test",
                PostgresqlDriverSettings.USERNAME_PROPERTY, "test",
                PostgresqlDriverSettings.PASSWORD_PROPERTY, "test",
                PostgresqlDriverSettings.TABLE_NAME_PROPERTY, SECRET + "-table"));
    }

    /**
     * 创建 health update mode 含敏感非法值的 Nacos 配置。
     *
     * @return 不可变测试配置；不可为空，线程安全。
     */
    private MapZeroConfig invalidNacosOptionConfig() {
        Map<String, String> values = new LinkedHashMap<>(nacosConfig(null).asMap());
        values.put(NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE, SECRET + "-mode");
        return new MapZeroConfig(values);
    }

    /**
     * 返回六个 Adapter 槽位的完整隔离配置。
     *
     * @return 可变、有序、非空、非线程安全配置 Map；仅由当前测试构造后立即复制。
     */
    private Map<String, String> allEnabledConfig() {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS, "127.0.0.1:9092"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, "assembly-client"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, "assembly-group"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, "assembly.topic"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, "assembly.reply"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true"),
                Map.entry(MongoDriverSettings.PROPERTY_MONGO_URI, "mongodb://127.0.0.1:27017"),
                Map.entry(MongoDriverSettings.PROPERTY_MONGO_DATABASE, "assembly"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true"),
                Map.entry(RedisDriverSettings.PROPERTY_REDIS_URI, "redis://127.0.0.1:6379/0"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, "assembly"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "contract"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED, "true"),
                Map.entry(PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                        "jdbc:postgresql://127.0.0.1:5432/assembly"),
                Map.entry(PostgresqlDriverSettings.USERNAME_PROPERTY, "assembly"),
                Map.entry(PostgresqlDriverSettings.PASSWORD_PROPERTY, "assembly"),
                Map.entry(PostgresqlDriverSettings.TABLE_NAME_PROPERTY, "assembly_data"),
                Map.entry(NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS),
                Map.entry(NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848"),
                Map.entry(NacosDiscoveryConfigKeys.NAMESPACE, "assembly"),
                Map.entry(NacosDiscoveryConfigKeys.DEFAULT_GROUP, "ASSEMBLY"),
                Map.entry(NacosDiscoveryConfigKeys.DEFAULT_CLUSTER, "ASSEMBLY")));
    }

    /**
     * 把完整异常图打印为文本，供配置原值反证。
     *
     * @param failure 待打印失败；不可为空。
     * @return 完整堆栈文本；不可为空，调用方可变，方法不修改异常图。
     */
    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }

    /**
     * 测试用字符串 value codec。
     *
     * @author zn
     */
    private static final class StringObjectCacheValueCodec implements CacheValueCodec<Object> {

        /** 单例。 */
        private static final StringObjectCacheValueCodec INSTANCE = new StringObjectCacheValueCodec();

        /** 阻止外部创建测试 codec。 */
        private StringObjectCacheValueCodec() {
        }

        /**
         * 返回 codec 稳定名称。
         *
         * @return codec 名称；不可为空，线程安全。
         */
        @Override
        public String name() {
            return "assembly-string-object";
        }

        /**
         * 编码测试值。
         *
         * @param value 测试值；不可为空。
         * @return payload；不可为空、可变，线程安全。
         */
        @Override
        public byte[] encode(final Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 解码测试值。
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
