package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * production starter 真实 Docker 环境外部集成测试。
 *
 * @author zn
 */
class ZeroProductionRuntimeExternalIT {

    /**
     * external-tests profile 标记。
     */
    private static final String EXTERNAL_TESTS_ENABLED = "zero.external.tests";

    /**
     * Kafka broker 地址系统属性。
     */
    private static final String KAFKA_BOOTSTRAP_PROPERTY =
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS;

    /**
     * Kafka broker 地址环境变量。
     */
    private static final String KAFKA_BOOTSTRAP_ENV =
            ZeroProductionRuntimeConfigKeys.ENV_RPC_KAFKA_BOOTSTRAP_SERVERS;

    /**
     * PostgreSQL JDBC URL 环境变量。
     */
    private static final String POSTGRESQL_URL_ENV = PostgresqlDriverSettings.JDBC_URL_ENV;

    /**
     * PostgreSQL 用户名环境变量。
     */
    private static final String POSTGRESQL_USER_ENV = PostgresqlDriverSettings.USERNAME_ENV;

    /**
     * PostgreSQL 密码环境变量。
     */
    private static final String POSTGRESQL_PASSWORD_ENV = PostgresqlDriverSettings.PASSWORD_ENV;

    /**
     * PostgreSQL 通用表环境变量。
     */
    private static final String POSTGRESQL_TABLE_ENV = PostgresqlDriverSettings.TABLE_NAME_ENV;

    /**
     * 验证 production opt-in 工厂可以连接 Docker 真实 Adapter 并输出健康诊断。
     */
    @Test
    void dockerRealEnvironmentShouldStartProductionAdaptersAndReportHealthy() {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must be enabled");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> config = externalConfig(suffix);

        try (ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory
                .externalTestBuilder(new MapZeroConfig(config))
                .redisCacheValueCodec(StringObjectCacheValueCodec.INSTANCE)
                .build()) {
            assertEquals(ZeroProductionAdapterState.CREATED,
                    runtime.report()
                            .adapterStatus(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC)
                            .orElseThrow()
                            .state());

            runtime.start();

            assertTrue(runtime.kafkaRpcAdapter().orElseThrow().delegate().isPresent());
            assertTrue(runtime.mongoClient().isPresent());
            assertTrue(runtime.redisClient().isPresent());
            assertTrue(runtime.postgresqlDataAdapter().isPresent());
            assertTrue(runtime.serviceDiscovery().isPresent());
            assertFalse(runtime.report().toString().contains(config.get(PostgresqlDriverSettings.PASSWORD_PROPERTY)));
            assertHealthy(runtime.report(), ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC);
            assertHealthy(runtime.report(), ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA);
            assertHealthy(runtime.report(), ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA);
            assertHealthy(runtime.report(), ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE);
            assertHealthy(runtime.report(), ZeroProductionRuntimeBuilder.ADAPTER_POSTGRESQL_DATA);
            assertHealthy(runtime.report(), ZeroProductionRuntimeBuilder.ADAPTER_NACOS_DISCOVERY);
        }
    }

    /**
     * 楠岃瘉澶栭儴 Adapter 鍚姩澶辫触浠呭叕寮€瀹夊叏 phase/ErrorCode锛屼笉淇濈暀杩炴帴鍊笺€?
     */
    @Test
    void unreachableKafkaShouldExposeSafeStartupFailure() {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must be enabled");
        String secretClientId = "external-failure-secret-client";
        String unavailableBootstrap = "127.0.0.1:1";
        Map<String, String> config = Map.ofEntries(
                Map.entry(ZeroRuntimeConfigKeys.ZERO_MODE, ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_BUDGET_MILLIS, "3000"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS, "1000"),
                Map.entry(ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS, unavailableBootstrap),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, secretClientId),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, "external-failure-group"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, "external.failure"),
                Map.entry(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, "external-failure-reply"));

        try (ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory
                .externalTestBuilder(new MapZeroConfig(config))
                .build()) {
            ProductionAdapterException failure = assertThrows(
                    ProductionAdapterException.class,
                    runtime::start);

            assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC, failure.adapterName());
            assertEquals(ProductionAdapterFailurePhase.STARTUP_HEALTH, failure.failurePhase());
            assertSame(ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED, failure.errorCode());
            assertSafeFailureText(failure, secretClientId, unavailableBootstrap);

            ZeroProductionAdapterStatus status = runtime.report()
                    .adapterStatus(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC)
                    .orElseThrow();
            assertEquals(ZeroProductionAdapterState.FAILED, status.state());
            assertEquals(ProductionAdapterFailurePhase.STARTUP_HEALTH, status.failurePhase());
            assertSame(ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED, status.errorCode());
            assertFalse(runtime.report().containsFragment(secretClientId));
            assertFalse(runtime.report().containsFragment(unavailableBootstrap));
        }
    }

    private Map<String, String> externalConfig(final String suffix) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ZeroRuntimeConfigKeys.ZERO_MODE, ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST);
        values.put(ZeroRuntimeConfigKeys.ZERO_NAME, "production-external-" + suffix);
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED, "true");
        values.put(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
                requiredSetting(KAFKA_BOOTSTRAP_PROPERTY, KAFKA_BOOTSTRAP_ENV));
        values.put(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, "zero-production-external-" + suffix);
        values.put(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID,
                "zero-production-external-" + suffix);
        values.put(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, "zero.rpc.production." + suffix);
        values.put(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, "production-reply-" + suffix);

        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true");
        values.put(MongoDriverSettings.PROPERTY_MONGO_URI,
                setting(
                        MongoDriverSettings.PROPERTY_MONGO_URI,
                        MongoDriverSettings.ENV_MONGO_URI,
                        MongoDriverSettings.DEFAULT_CONNECTION_STRING));
        values.put(MongoDriverSettings.PROPERTY_MONGO_DATABASE,
                setting(
                        MongoDriverSettings.PROPERTY_MONGO_DATABASE,
                        MongoDriverSettings.ENV_MONGO_DATABASE,
                        MongoDriverSettings.DEFAULT_DATABASE_NAME));

        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true");
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true");
        values.put(RedisDriverSettings.PROPERTY_REDIS_URI,
                setting(
                        RedisDriverSettings.PROPERTY_REDIS_URI,
                        RedisDriverSettings.ENV_REDIS_URI,
                        RedisDriverSettings.DEFAULT_URI));
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, "zero_external_" + suffix);
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "runtime");

        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED, "true");
        values.put(PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                requiredSetting(PostgresqlDriverSettings.JDBC_URL_PROPERTY, POSTGRESQL_URL_ENV));
        values.put(PostgresqlDriverSettings.USERNAME_PROPERTY,
                requiredSetting(PostgresqlDriverSettings.USERNAME_PROPERTY, POSTGRESQL_USER_ENV));
        values.put(PostgresqlDriverSettings.PASSWORD_PROPERTY,
                requiredSetting(PostgresqlDriverSettings.PASSWORD_PROPERTY, POSTGRESQL_PASSWORD_ENV));
        values.put(PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
                setting(
                        PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
                        POSTGRESQL_TABLE_ENV,
                        "zero_external_" + suffix));

        values.put(NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS);
        values.put(NacosDiscoveryConfigKeys.SERVER_ADDR, requiredSetting(
                NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR,
                NacosDiscoveryConfigKeys.ENV_SERVER_ADDR));
        values.put(NacosDiscoveryConfigKeys.NAMESPACE, requiredSetting(
                NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE,
                NacosDiscoveryConfigKeys.ENV_NAMESPACE));
        values.put(NacosDiscoveryConfigKeys.DEFAULT_GROUP, setting(
                NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_GROUP,
                NacosDiscoveryConfigKeys.ENV_DEFAULT_GROUP,
                "ZERO_EXTERNAL_" + suffix));
        values.put(NacosDiscoveryConfigKeys.DEFAULT_CLUSTER, setting(
                NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_CLUSTER,
                NacosDiscoveryConfigKeys.ENV_DEFAULT_CLUSTER,
                "ZERO_EXTERNAL_" + suffix));
        values.put(NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS, "10000");
        putOptional(values, NacosDiscoveryConfigKeys.USERNAME,
                setting(NacosDiscoveryConfigKeys.SYSTEM_USERNAME, NacosDiscoveryConfigKeys.ENV_USERNAME, null));
        putOptional(values, NacosDiscoveryConfigKeys.PASSWORD,
                setting(NacosDiscoveryConfigKeys.SYSTEM_PASSWORD, NacosDiscoveryConfigKeys.ENV_PASSWORD, null));
        putOptional(values, NacosDiscoveryConfigKeys.ACCESS_KEY,
                setting(NacosDiscoveryConfigKeys.SYSTEM_ACCESS_KEY, NacosDiscoveryConfigKeys.ENV_ACCESS_KEY, null));
        putOptional(values, NacosDiscoveryConfigKeys.SECRET_KEY,
                setting(NacosDiscoveryConfigKeys.SYSTEM_SECRET_KEY, NacosDiscoveryConfigKeys.ENV_SECRET_KEY, null));
        return values;
    }

    private void assertSafeFailureText(
            final Throwable failure,
            final String... forbiddenFragments) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        for (String forbiddenFragment : forbiddenFragments) {
            assertFalse(output.toString().contains(forbiddenFragment));
            assertFalse(failure.toString().contains(forbiddenFragment));
        }
    }

    private void assertHealthy(final ZeroProductionAssemblyReport report, final String adapterName) {
        assertEquals(ZeroProductionAdapterState.HEALTHY,
                report.adapterStatus(adapterName).orElseThrow().state(),
                adapterName + " should be healthy");
    }

    private void putOptional(final Map<String, String> values, final String key, final String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private String requiredSetting(final String property, final String environment) {
        String current = setting(property, environment, null);
        assertNotNull(current, property + " or " + environment + " must be set");
        return current;
    }

    private String setting(final String property, final String environment, final String defaultValue) {
        return Optional.ofNullable(System.getProperty(property))
                .or(() -> Optional.ofNullable(System.getenv(environment)))
                .filter(value -> !value.isBlank())
                .orElse(defaultValue);
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
