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
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDataAdapter;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.runtime.cache.CacheRuntime;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.diagnostics.RuntimePhaseOutcome;
import group.zn.zero.runtime.discovery.DiscoveryRuntime;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import group.zn.zero.runtime.rpc.RpcRuntime;
import group.zn.zero.runtime.spi.ComponentKind;
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
                    runtime.require(DiscoveryRuntime.SERVICE_DISCOVERY));
            assertEquals(1_250, adapter.settings().requestTimeoutMillis());
        } finally {
            runtime.close();
        }

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedBuilder(nacosConfig("2000")).diagnose());
        assertEquals(ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY, failure.adapterName());
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
     * 验证所有已迁移 Adapter 使用正式中立 startup health，不再保留 legacy health lifecycle。
     */
    @Test
    void everyEnabledAdapterSlotShouldInstallOneMandatoryStartupHealth() {
        ZeroProductionRuntime runtime = isolatedBuilder(new MapZeroConfig(allEnabledConfig()))
                .redisCacheValueCodec(StringObjectCacheValueCodec.INSTANCE)
                .build();
        try {
            var kafkaPlan = runtime.plan().components().stream()
                    .filter(component -> StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC.equals(
                            component.componentId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ComponentKind.EXTERNAL, kafkaPlan.kind());
            assertTrue(kafkaPlan.requires().contains(LogRuntime.LOG_APPENDER));
            assertTrue(kafkaPlan.provides().contains(RpcRuntime.RPC_TRANSPORT));
            assertTrue(kafkaPlan.provides().contains(RpcRuntime.RPC_HANDLER_REGISTRY));
            assertTrue(kafkaPlan.selectionReasons().stream().allMatch(reason ->
                    StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC.equals(reason.providerId())));

            var kafkaConfig = runtime.plan().config().stream()
                    .filter(metadata -> StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC.equals(metadata.owner()))
                    .toList();
            assertEquals(8, kafkaConfig.size());
            assertEquals(5, kafkaConfig.stream().filter(metadata -> metadata.sensitive()).count());

            var kafkaStatus = runtime.report().components().stream()
                    .filter(component -> StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC.equals(
                            component.componentId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RuntimePhaseOutcome.NOT_RUN, kafkaStatus.startupHealth());

            var mongoPlan = runtime.plan().components().stream()
                    .filter(component -> StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA.equals(
                            component.componentId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ComponentKind.EXTERNAL, mongoPlan.kind());
            assertTrue(mongoPlan.provides().contains(DataRuntime.DATA_SERVICES));
            assertTrue(mongoPlan.selectionReasons().stream().allMatch(reason ->
                    StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA.equals(reason.providerId())));

            var mongoConfig = runtime.plan().config().stream()
                    .filter(metadata -> StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA.equals(metadata.owner()))
                    .toList();
            assertEquals(2, mongoConfig.size());
            assertTrue(mongoConfig.stream().allMatch(metadata -> metadata.sensitive()));
            assertInstanceOf(
                    MongoDataAdapter.class,
                    runtime.requireAll(DataRuntime.DATA_SERVICES).getFirst());
            assertPostgresqlAssembly(runtime);
            assertRedisAssembly(runtime);
            assertNacosAssembly(runtime);

            var mongoStatus = runtime.report().components().stream()
                    .filter(component -> StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA.equals(
                            component.componentId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RuntimePhaseOutcome.NOT_RUN, mongoStatus.startupHealth());
        } finally {
            runtime.close();
        }
        assertEquals(0, runtime.report().pendingResourceCloseCount());
    }

    private void assertPostgresqlAssembly(final ZeroProductionRuntime runtime) {
        var plan = runtime.plan().components().stream()
                .filter(component -> group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA.equals(component.componentId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ComponentKind.EXTERNAL, plan.kind());
        assertTrue(plan.provides().contains(DataRuntime.DATA_SERVICES));
        assertTrue(plan.selectionReasons().stream().allMatch(reason ->
                group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA.equals(reason.providerId())));
        assertConfigMetadata(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA, 4, 4);
        assertTrue(runtime.requireAll(DataRuntime.DATA_SERVICES).stream()
                .anyMatch(PostgresqlDataAdapter.class::isInstance));
        assertStartupHealthNotRun(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA);
    }

    private void assertNacosAssembly(final ZeroProductionRuntime runtime) {
        var plans = runtime.plan().components();
        var discoveryPlan = plans.stream()
                .filter(component -> group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY.equals(component.componentId()))
                .findFirst()
                .orElseThrow();
        var resolverPlan = plans.stream()
                .filter(component -> group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER.equals(component.componentId()))
                .findFirst()
                .orElseThrow();

        assertEquals(ComponentKind.EXTERNAL, discoveryPlan.kind());
        assertTrue(discoveryPlan.provides().contains(DiscoveryRuntime.SERVICE_DISCOVERY));
        assertEquals(ComponentKind.FOUNDATION, resolverPlan.kind());
        assertTrue(resolverPlan.requires().contains(DiscoveryRuntime.SERVICE_DISCOVERY));
        assertTrue(resolverPlan.provides().contains(RpcRuntime.RPC_SERVICE_RESOLVER));
        assertConfigMetadata(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY, 11, 8);
        assertConfigMetadata(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER, 0, 0);
        assertInstanceOf(
                NacosDiscoveryAdapter.class,
                runtime.require(DiscoveryRuntime.SERVICE_DISCOVERY));
        assertTrue(runtime.optional(RpcRuntime.RPC_SERVICE_RESOLVER).isPresent());
        assertStartupHealthNotRun(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY);
    }

    /** 验证 Mongo build 不要求服务可达，且 client 由中立 build resource ledger 持有和关闭。 */
    @Test
    void mongoBuildShouldNotRequireReachabilityAndShouldUseNeutralResourceLedger() {
        ZeroProductionRuntime baseline = isolatedBuilder(new MapZeroConfig(Map.of())).build();
        int baselineResourceCount;
        try {
            baselineResourceCount = baseline.report().buildResourceCount();
        } finally {
            baseline.close();
        }
        ZeroProductionRuntime runtime = isolatedBuilder(new MapZeroConfig(Map.of(
                        ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true",
                        MongoDriverSettings.PROPERTY_MONGO_URI, "mongodb://203.0.113.1:27017",
                        MongoDriverSettings.PROPERTY_MONGO_DATABASE, "assembly")))
                .build();

        assertTrue(runtime.requireAll(DataRuntime.DATA_SERVICES).stream()
                .anyMatch(MongoDataAdapter.class::isInstance));
        assertEquals(baselineResourceCount + 2, runtime.report().buildResourceCount());
        assertEquals(baselineResourceCount + 2, runtime.report().pendingResourceCloseCount());
        runtime.close();
        assertEquals(0, runtime.report().pendingResourceCloseCount());
    }

    /** 验证 provider create 失败从脱敏 diagnostic 恢复 Mongo client-creation 归因。 */
    @Test
    void invalidMongoUriShouldRemainSafeClientCreationFailure() {
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedBuilder(new MapZeroConfig(Map.of(
                                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED, "true",
                                MongoDriverSettings.PROPERTY_MONGO_URI, SECRET,
                                MongoDriverSettings.PROPERTY_MONGO_DATABASE, "assembly")))
                        .build());

        assertEquals(ProductionAdapterNames.ADAPTER_MONGO_DATA, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CLIENT_CREATION, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.CLIENT_CREATION_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(), failure.message());
        assertNull(failure.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
    }

    /** 验证 Redis data/cache 共用一个中立 ledger client，且 build 不要求 Redis 可达。 */
    @Test
    void redisProvidersShouldShareOneNeutralResource() {
        ZeroProductionRuntime baseline = isolatedBuilder(new MapZeroConfig(Map.of())).build();
        int baselineResourceCount;
        try {
            baselineResourceCount = baseline.report().buildResourceCount();
        } finally {
            baseline.close();
        }
        ZeroProductionRuntime runtime = isolatedBuilder(new MapZeroConfig(Map.of(
                        ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true",
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                        RedisDriverSettings.PROPERTY_REDIS_URI, "redis://203.0.113.1:6379/0",
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, "assembly",
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "contract")))
                .redisCacheValueCodec(StringObjectCacheValueCodec.INSTANCE)
                .build();

        assertTrue(runtime.requireAll(DataRuntime.DATA_SERVICES).stream()
                .anyMatch(RedisDataAdapter.class::isInstance));
        assertInstanceOf(
                RedisDistributedCacheService.class,
                runtime.require(CacheRuntime.CACHE_SERVICE));
        assertEquals(baselineResourceCount + 2, runtime.report().buildResourceCount());
        assertEquals(baselineResourceCount + 2, runtime.report().pendingResourceCloseCount());
        runtime.close();
        assertEquals(0, runtime.report().pendingResourceCloseCount());
    }

    /** 验证共享 Redis client 创建失败仍按 data 优先 owner 安全归因。 */
    @Test
    void invalidSharedRedisUriShouldRemainSafeDataClientCreationFailure() {
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> isolatedBuilder(new MapZeroConfig(Map.of(
                                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true",
                                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                                RedisDriverSettings.PROPERTY_REDIS_URI, SECRET,
                                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, "assembly",
                                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "contract")))
                        .redisCacheValueCodec(StringObjectCacheValueCodec.INSTANCE)
                        .build());

        assertEquals(ProductionAdapterNames.ADAPTER_REDIS_DATA, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CLIENT_CREATION, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.CLIENT_CREATION_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(), failure.message());
        assertNull(failure.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
    }

    private void assertRedisAssembly(final ZeroProductionRuntime runtime) {
        var plans = runtime.plan().components();
        var resourcePlan = plans.stream()
                .filter(component -> group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE.equals(component.componentId()))
                .findFirst()
                .orElseThrow();
        var dataPlan = plans.stream()
                .filter(component -> group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA.equals(component.componentId()))
                .findFirst()
                .orElseThrow();
        var cachePlan = plans.stream()
                .filter(component -> group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_CACHE.equals(component.componentId()))
                .findFirst()
                .orElseThrow();

        assertEquals(ComponentKind.FOUNDATION, resourcePlan.kind());
        assertTrue(resourcePlan.provides().stream().anyMatch(key ->
                key.id().equals(StandardRuntimeCapabilityModel.REDIS_RESOURCE)));
        assertEquals(ComponentKind.EXTERNAL, dataPlan.kind());
        assertTrue(dataPlan.requires().stream().anyMatch(key ->
                key.id().equals(StandardRuntimeCapabilityModel.REDIS_RESOURCE)));
        assertTrue(dataPlan.provides().contains(DataRuntime.DATA_SERVICES));
        assertEquals(ComponentKind.EXTERNAL, cachePlan.kind());
        assertTrue(cachePlan.requires().stream().anyMatch(key ->
                key.id().equals(StandardRuntimeCapabilityModel.REDIS_RESOURCE)));
        assertTrue(cachePlan.provides().contains(CacheRuntime.CACHE_SERVICE));
        assertTrue(cachePlan.startAfter().contains(group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA));

        assertConfigMetadata(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE, 1, 1);
        assertConfigMetadata(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA, 0, 0);
        assertConfigMetadata(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_CACHE, 2, 2);
        Set<Class<?>> dataServiceTypes = runtime.requireAll(DataRuntime.DATA_SERVICES)
                .stream()
                .map(Object::getClass)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(
                Set.of(MongoDataAdapter.class, PostgresqlDataAdapter.class, RedisDataAdapter.class),
                dataServiceTypes);
        assertInstanceOf(
                RedisDistributedCacheService.class,
                runtime.require(CacheRuntime.CACHE_SERVICE));
        // Mongo, shared Redis, Nacos and the PostgreSQL pool, followed by each repository factory.
        assertEquals(4 + runtime.requireAll(DataRuntime.REPOSITORY_SOURCES).size(), runtime.report().buildResourceCount());
        assertStartupHealthNotRun(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA);
        assertStartupHealthNotRun(runtime, group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_REDIS_CACHE);
    }

    private void assertConfigMetadata(
            final ZeroProductionRuntime runtime,
            final group.zn.zero.runtime.api.ComponentId owner,
            final int expectedCount,
            final long expectedSensitiveCount) {
        var metadata = runtime.plan().config().stream()
                .filter(value -> owner.equals(value.owner()))
                .toList();
        assertEquals(expectedCount, metadata.size());
        assertEquals(expectedSensitiveCount, metadata.stream().filter(value -> value.sensitive()).count());
    }

    private void assertStartupHealthNotRun(
            final ZeroProductionRuntime runtime,
            final group.zn.zero.runtime.api.ComponentId componentId) {
        var status = runtime.report().components().stream()
                .filter(component -> componentId.equals(component.componentId()))
                .findFirst()
                .orElseThrow();
        assertEquals(RuntimePhaseOutcome.NOT_RUN, status.startupHealth());
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
