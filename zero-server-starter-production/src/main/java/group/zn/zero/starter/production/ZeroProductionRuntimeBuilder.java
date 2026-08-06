package group.zn.zero.starter.production;

import com.mongodb.client.MongoClient;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDataAdapter;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.RedisCacheEnvelopeCodec;
import group.zn.zero.data.redis.RedisCacheStore;
import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverClientFactory;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoveryFactory;
import group.zn.zero.discovery.nacos.NacosDiscoverySettings;
import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.discovery.nacos.ServiceDiscoveryRpcServiceResolver;
import group.zn.zero.log.LogSink;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.starter.ZeroRuntimeComponents;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeBuilder;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroRuntimeFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import redis.clients.jedis.RedisClient;

/**
 * production/external-test runtime 显式装配构建器。
 *
 * <p>该 builder 只在调用方明确选择 production 模块时创建真实 Adapter；默认 builder 不做 classpath
 * 自动发现，不会改变 `ZeroRuntimeFactory.localDefault` 行为。
 *
 * @author zn
 */
public final class ZeroProductionRuntimeBuilder {

    /**
     * Kafka RPC Adapter 名称。
     */
    public static final String ADAPTER_KAFKA_RPC = "kafka-rpc";

    /**
     * MongoDB data Adapter 名称。
     */
    public static final String ADAPTER_MONGO_DATA = "mongo-data";

    /**
     * Redis data Adapter 名称。
     */
    public static final String ADAPTER_REDIS_DATA = "redis-data";

    /**
     * Redis cache Adapter 名称。
     */
    public static final String ADAPTER_REDIS_CACHE = "redis-cache";

    /**
     * PostgreSQL data Adapter 名称。
     */
    public static final String ADAPTER_POSTGRESQL_DATA = "postgresql-data";

    /**
     * Nacos discovery Adapter 名称。
     */
    public static final String ADAPTER_NACOS_DISCOVERY = "nacos-discovery";

    /** 允许作为公共 Kafka client 安全属性的精确键。 */
    private static final Set<String> ALLOWED_KAFKA_CLIENT_PROPERTY_KEYS = Set.of("security.protocol");

    /** 允许作为公共 Kafka client 安全属性的键前缀。 */
    private static final List<String> ALLOWED_KAFKA_CLIENT_PROPERTY_PREFIXES = List.of("ssl.", "sasl.");

    /**
     * runtime profile。
     */
    private final String profile;

    /**
     * 统一配置。
     */
    private final ZeroConfig config;

    /** JVM system property 读取函数，默认读取当前进程。 */
    private Function<String, String> systemPropertyLookup = System::getProperty;

    /** 进程环境变量读取函数，默认读取当前进程。 */
    private Function<String, String> environmentLookup = System::getenv;

    /**
     * 终端日志落地 SPI。
     */
    private LogSink terminalLogSink;

    /**
     * 执行器装配点。
     */
    private ZeroRuntimeExecutors executors;

    /**
     * Redis cache value codec。
     */
    private CacheValueCodec<Object> redisCacheValueCodec;

    /** 经过白名单校验、同时应用于 Kafka producer/consumer/Admin 的客户端属性。 */
    private Map<String, Object> kafkaClientProperties = Map.of();

    /**
     * 创建 production runtime builder。
     *
     * @param profile runtime profile；不可为空。
     * @param config 统一配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空。
     * @param executors 执行器装配点；不可为空。
     */
    ZeroProductionRuntimeBuilder(
            final String profile,
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.config = Objects.requireNonNull(config, "config");
        this.terminalLogSink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /**
     * 覆盖 production 配置的进程属性与环境变量读取函数。
     *
     * <p>该包级 seam 仅用于让测试显式隔离宿主进程配置；标准 production 工厂始终保留读取当前进程
     * system property 与 environment 的默认行为。调用只修改尚未使用的 builder，不读取配置、不创建资源。
     *
     * @param systemPropertyLookup JVM system property 读取函数；不可为空，可返回空。
     * @param environmentLookup 进程环境变量读取函数；不可为空，可返回空。
     * @return 当前 builder；不可为空，非线程安全。
     * @throws NullPointerException 任一读取函数为空时抛出。
     */
    ZeroProductionRuntimeBuilder configSourceLookups(
            final Function<String, String> systemPropertyLookup,
            final Function<String, String> environmentLookup) {
        this.systemPropertyLookup = Objects.requireNonNull(systemPropertyLookup, "systemPropertyLookup");
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
        return this;
    }

    /**
     * 覆盖终端日志落地 SPI。
     *
     * <p>该 SPI 只在 runtime 构建期间传给 Starter 装配器，并始终由安全日志 pipeline 包装，
     * 不会直接暴露给 observer 或业务组件。</p>
     *
     * @param terminalLogSink 终端日志落地 SPI；不可为空。
     * @return 当前 builder；不可为空，非线程安全。
     * @throws NullPointerException 当终端日志 SPI 为空时抛出。
     */
    public ZeroProductionRuntimeBuilder terminalLogSink(final LogSink terminalLogSink) {
        this.terminalLogSink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        return this;
    }

    /**
     * 覆盖执行器装配点。
     *
     * @param executors 执行器装配点；不可为空。
     * @return 当前 builder；不可为空，非线程安全。
     * @throws NullPointerException 执行器装配点为空时抛出。
     */
    public ZeroProductionRuntimeBuilder executors(final ZeroRuntimeExecutors executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
        return this;
    }

    /**
     * 设置 Redis cache value codec。
     *
     * <p>启用 Redis cache 时必须提供业务可接受的 value codec；builder 不会隐式选择 Java 序列化或
     * `toString()` 降级，以免污染生产缓存格式。
     *
     * @param redisCacheValueCodec Redis cache value codec；不可为空。
     * @return 当前 builder；不可为空，非线程安全。
     * @throws NullPointerException codec 为空时抛出。
     */
    public ZeroProductionRuntimeBuilder redisCacheValueCodec(
            final CacheValueCodec<Object> redisCacheValueCodec) {
        this.redisCacheValueCodec = Objects.requireNonNull(redisCacheValueCodec, "redisCacheValueCodec");
        return this;
    }

    /**
     * 设置 Kafka producer、consumer 与 startup Admin health 共用的安全客户端属性。
     *
     * <p>仅接受 {@code security.protocol}、{@code ssl.*} 与 {@code sasl.*}。bootstrap、client id、
     * group、serializer、deserializer 和 timeout 等框架受管属性不能通过该入口覆盖。Map 不进入诊断报告、
     * 日志或 settings 文本。
     *
     * @param properties Kafka 安全客户端属性；不可为空，可为空集合，调用后会复制。
     * @return 当前 builder；不可为空，非线程安全。
     * @throws ProductionAdapterException 属性键不在白名单时抛出安全配置异常。
     * @throws NullPointerException Map、键或值为空时抛出。
     */
    public ZeroProductionRuntimeBuilder kafkaClientProperties(final Map<String, ?> properties) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : Objects.requireNonNull(properties, "properties").entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "property key");
            Object value = Objects.requireNonNull(entry.getValue(), "property value");
            if (!allowedKafkaClientProperty(key)) {
                throw ProductionAdapterFailures.invalidConfig(
                        ADAPTER_KAFKA_RPC,
                        ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                        "builder.kafkaClientProperties");
            }
            copied.put(key, value);
        }
        this.kafkaClientProperties = Map.copyOf(copied);
        return this;
    }

    /**
     * 生成脱敏装配诊断报告，不创建真实 Adapter、不修改业务数据；builder 非线程安全。
     *
     * @return 诊断报告；不可为空，线程安全。
     * @throws ProductionAdapterException production 配置缺失或非法时抛出安全配置异常。
     */
    public ZeroProductionAssemblyReport diagnose() {
        try {
            ResolvedAdapters resolved = resolveAdapters();
            return reportFromDiagnostics(resolved.diagnostics(), null, List.of());
        } catch (ProductionAdapterException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw ProductionAdapterFailures.sanitize(
                    "production-runtime",
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ProductionAdapterErrorCode.CONFIG_INVALID,
                    ProductionAdapterErrorCode.CONFIG_INVALID.message(),
                    failure);
        }
    }

    /**
     * 构建 production runtime；创建并登记外部 client，失败时逆序回滚；builder 非线程安全且不修改业务数据。
     *
     * @return production runtime；不可为空，未启动，线程安全性由内部组件声明。
     * @throws ProductionAdapterException 缺失必填配置、配置非法或 build 资源创建失败时抛出安全异常。
     */
    public ZeroProductionRuntime build() {
        try {
            ResolvedAdapters resolved = resolveAdapters();
            failIfMissing(resolved);
            BuildAssembly assembly = buildRuntime(resolved);
            return new ZeroProductionRuntime(
                    profile,
                    assembly.components(),
                    resolved.diagnostics(),
                    assembly.closeables(),
                    resolved.startupBudget(),
                    assembly.adapters());
        } catch (ProductionAdapterException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw ProductionAdapterFailures.sanitize(
                    "production-runtime",
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ProductionAdapterErrorCode.CONFIG_INVALID,
                    ProductionAdapterErrorCode.CONFIG_INVALID.message(),
                    failure);
        }
    }

    private BuildAssembly buildRuntime(final ResolvedAdapters resolved) {
        ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, terminalLogSink, executors);
        ProductionResourceScope resourceScope = new ProductionResourceScope();
        try {
            KafkaRpcLifecycleAdapter kafka = configureKafka(
                    builder, resolved.kafka(), resolved.startupBudget());
            MongoBuildResources mongo = configureMongo(
                    builder, resolved.mongo(), resolved.startupBudget(), resourceScope);
            RedisBuildResources redis = configureRedis(
                    builder, resolved, resolved.startupBudget(), resourceScope);
            PostgresqlDataAdapter postgresql = configurePostgresql(
                    builder, resolved.postgresql(), resolved.startupBudget());
            NacosBuildResources nacos = configureNacos(
                    builder, resolved.nacos(), resolved.startupBudget());
            ZeroRuntimeComponents components = builder.build();
            return new BuildAssembly(
                    components,
                    resourceScope.commit(),
                    new ZeroProductionRuntime.RuntimeAdapters(
                            kafka,
                            mongo.adapter(),
                            mongo.client(),
                            redis.adapter(),
                            redis.client(),
                            postgresql,
                            nacos.discovery(),
                            nacos.rpcServiceResolver()));
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    "production-runtime",
                    ProductionAdapterFailurePhase.CLIENT_CREATION,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                    failure);
            resourceScope.rollback(safeFailure);
            throw safeFailure;
        }
    }

    /**
     * 装配 Kafka RPC Adapter 及其可选健康检查。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved Kafka 解析结果；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @return 启用时返回 Adapter，否则返回 null；非线程安全。
     */
    private KafkaRpcLifecycleAdapter configureKafka(
            final ZeroRuntimeBuilder builder,
            final ResolvedKafka resolved,
            final ProductionStartupBudget startupBudget) {
        if (!resolved.enabled()) {
            return null;
        }
        ProductionAdapterDiagnostic diagnostic = resolved.diagnostic();
        RpcTransportObserver observer = new KafkaProductionTelemetryObserver(builder.logAppender());
        KafkaRpcLifecycleAdapter kafka = new KafkaRpcLifecycleAdapter(resolved.settings(), observer);
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        builder.rpcTransport(kafka)
                .rpcHandlerRegistry(kafka)
                .addLifecycleComponent(new ProductionAdapterLifecycle(
                        diagnostic,
                        startupBudget,
                        kafka,
                        startupBudget.adapterBudget()))
                .addLifecycleComponent(new AdapterHealthCheckLifecycle(
                        diagnostic,
                        new KafkaClusterHealthCheck(resolved.settings(), kafkaClientProperties),
                        startupBudget));
        return kafka;
    }

    /**
     * 装配 MongoDB Adapter、驱动客户端及其健康检查。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved MongoDB 解析结果；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param resourceScope build 资源事务；不可为空，当前方法会登记已创建 client。
     * @return MongoDB 构建资源；不可为空，禁用时各组件为空。
     */
    private MongoBuildResources configureMongo(
            final ZeroRuntimeBuilder builder,
            final ResolvedMongo resolved,
            final ProductionStartupBudget startupBudget,
            final ProductionResourceScope resourceScope) {
        if (!resolved.enabled()) {
            return MongoBuildResources.disabled();
        }
        ProductionAdapterDiagnostic diagnostic = resolved.diagnostic();
        MongoDataAdapter adapter = new MongoDataAdapter();
        MongoClient client = createAdapterResource(
                diagnostic,
                () -> adapter.createClient(resolved.settings(), startupBudget.adapterBudget()));
        ManagedCloseableLifecycle closeable = resourceScope.register(
                new ManagedCloseableLifecycle(ADAPTER_MONGO_DATA, client));
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        builder.addLifecycleComponent(new ProductionAdapterLifecycle(diagnostic, startupBudget, adapter))
                .addLifecycleComponent(closeable)
                .addLifecycleComponent(new AdapterHealthCheckLifecycle(
                        diagnostic,
                        timeout -> ProductionAdapterHealthProbes.mongo(resolved.settings(), timeout),
                        startupBudget));
        return new MongoBuildResources(adapter, client);
    }

    /**
     * 装配共享 Redis 客户端以及按需启用的 data/cache 能力。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved 全部 Adapter 解析结果；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param resourceScope build 资源事务；不可为空，当前方法会登记已创建 client。
     * @return Redis 构建资源；不可为空，禁用时各组件为空。
     */
    private RedisBuildResources configureRedis(
            final ZeroRuntimeBuilder builder,
            final ResolvedAdapters resolved,
            final ProductionStartupBudget startupBudget,
            final ProductionResourceScope resourceScope) {
        if (!resolved.redis().enabled()) {
            return RedisBuildResources.disabled();
        }
        String owner = resolved.redisData().enabled() ? ADAPTER_REDIS_DATA : ADAPTER_REDIS_CACHE;
        RedisClient client = createRedisClient(resolved, startupBudget.adapterBudget(), owner);
        ManagedCloseableLifecycle closeable = resourceScope.register(
                new ManagedCloseableLifecycle(owner, client));
        builder.addLifecycleComponent(closeable);
        RedisDataAdapter adapter = configureRedisData(
                builder, resolved.redisData(), resolved.redis().settings(), startupBudget);
        configureRedisCache(
                builder, resolved.redisCache(), resolved.redis().settings(), client, startupBudget);
        return new RedisBuildResources(adapter, client);
    }

    /**
     * 装配 Redis data Adapter。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved Redis data 解析结果；不可为空。
     * @param settings Redis driver 配置；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @return 启用时返回 Adapter，否则返回 null；非线程安全。
     */
    private RedisDataAdapter configureRedisData(
            final ZeroRuntimeBuilder builder,
            final ResolvedRedisData resolved,
            final RedisDriverSettings settings,
            final ProductionStartupBudget startupBudget) {
        if (!resolved.enabled()) {
            return null;
        }
        ProductionAdapterDiagnostic diagnostic = resolved.diagnostic();
        RedisDataAdapter adapter = new RedisDataAdapter();
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        builder.addLifecycleComponent(new ProductionAdapterLifecycle(diagnostic, startupBudget, adapter))
                .addLifecycleComponent(new AdapterHealthCheckLifecycle(
                        diagnostic,
                        timeout -> ProductionAdapterHealthProbes.redisData(settings, timeout),
                        startupBudget));
        return adapter;
    }

    /**
     * 装配 Redis cache 能力。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved Redis cache 解析结果；不可为空。
     * @param settings Redis driver 配置；不可为空。
     * @param client 共享 Redis 客户端；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     */
    private void configureRedisCache(
            final ZeroRuntimeBuilder builder,
            final ResolvedRedisCache resolved,
            final RedisDriverSettings settings,
            final RedisClient client,
            final ProductionStartupBudget startupBudget) {
        if (!resolved.enabled()) {
            return;
        }
        ProductionAdapterDiagnostic diagnostic = resolved.diagnostic();
        CacheService<Object, Object> cacheService = redisCacheService(client, resolved);
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        builder.cacheService(cacheService)
                .addLifecycleComponent(ProductionAdapterLifecycle.marker(diagnostic, startupBudget))
                .addLifecycleComponent(new AdapterHealthCheckLifecycle(
                        diagnostic,
                        timeout -> ProductionAdapterHealthProbes.redisCache(settings, timeout),
                        startupBudget));
    }

    /**
     * 装配 PostgreSQL Adapter 及其可选健康检查。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved PostgreSQL 解析结果；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @return 启用时返回 Adapter，否则返回 null；非线程安全。
     */
    private PostgresqlDataAdapter configurePostgresql(
            final ZeroRuntimeBuilder builder,
            final ResolvedPostgresql resolved,
            final ProductionStartupBudget startupBudget) {
        if (!resolved.enabled()) {
            return null;
        }
        ProductionAdapterDiagnostic diagnostic = resolved.diagnostic();
        PostgresqlDataAdapter adapter = new PostgresqlDataAdapter();
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        builder.addLifecycleComponent(new ProductionAdapterLifecycle(diagnostic, startupBudget, adapter))
                .addLifecycleComponent(new AdapterHealthCheckLifecycle(
                        diagnostic,
                        timeout -> ProductionAdapterHealthProbes.postgresql(resolved.settings(), timeout),
                        startupBudget));
        return adapter;
    }

    /**
     * 装配 Nacos 服务发现和 RPC resolver。
     *
     * @param builder runtime builder；不可为空，当前方法会修改其装配状态。
     * @param resolved Nacos 解析结果；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @return Nacos 构建资源；不可为空，禁用时各组件为空。
     */
    private NacosBuildResources configureNacos(
            final ZeroRuntimeBuilder builder,
            final ResolvedNacos resolved,
            final ProductionStartupBudget startupBudget) {
        if (!resolved.enabled()) {
            return NacosBuildResources.disabled();
        }
        ProductionAdapterDiagnostic diagnostic = resolved.diagnostic();
        ServiceDiscovery discovery = createAdapterResource(
                diagnostic,
                () -> NacosDiscoveryFactory.nacos(resolved.settings()));
        RpcServiceResolver rpcServiceResolver = new ServiceDiscoveryRpcServiceResolver(discovery);
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        builder.addLifecycleComponent(new ProductionAdapterLifecycle(
                        diagnostic,
                        startupBudget,
                        discovery,
                        Duration.ofMillis(resolved.settings().requestTimeoutMillis())))
                .addLifecycleComponent(new AdapterHealthCheckLifecycle(
                        diagnostic,
                        timeout -> verifyDiscoveryRunning(discovery),
                        startupBudget));
        return new NacosBuildResources(discovery, rpcServiceResolver);
    }

    /**
     * 校验 Nacos discovery 已进入运行状态。
     *
     * @param discovery 服务发现实例；不可为空。
     * @throws ProductionAdapterException 服务发现未运行时抛出安全异常。
     */
    private void verifyDiscoveryRunning(final ServiceDiscovery discovery) {
        if (!discovery.running()) {
            throw ProductionAdapterFailures.failure(
                    ADAPTER_NACOS_DISCOVERY,
                    ProductionAdapterFailurePhase.STARTUP_HEALTH,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED.message());
        }
    }

    private CacheService<Object, Object> redisCacheService(
            final RedisClient redisClient,
            final ResolvedRedisCache resolved) {
        return new RedisDistributedCacheService<>(
                "redis",
                CachePolicy.defaults(),
                new RedisCacheStore<>(
                        redisClient,
                        resolved.namespace(),
                        resolved.cacheName(),
                        1,
                        new group.zn.zero.data.redis.DefaultRedisCacheKeyStrategy(),
                        group.zn.zero.cache.CacheKeyCodecs.defaults(),
                        redisCacheValueCodec,
                        new RedisCacheEnvelopeCodec()));
    }

    private <T> T createAdapterResource(
            final ProductionAdapterDiagnostic diagnostic,
            final Supplier<T> factory) {
        try {
            return Objects.requireNonNull(factory, "factory").get();
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    diagnostic.adapterName(),
                    ProductionAdapterFailurePhase.CLIENT_CREATION,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                    failure);
            diagnostic.fail(safeFailure);
            throw safeFailure;
        }
    }

    private RedisClient createRedisClient(
            final ResolvedAdapters resolved,
            final Duration timeout,
            final String owner) {
        try {
            return RedisDriverClientFactory.create(resolved.redis().settings(), timeout);
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    owner,
                    ProductionAdapterFailurePhase.CLIENT_CREATION,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                    failure);
            failIfEnabled(resolved.redisData(), safeFailure);
            failIfEnabled(resolved.redisCache(), safeFailure);
            throw safeFailure;
        }
    }

    private void failIfEnabled(
            final ResolvedRedisData resolved,
            final ProductionAdapterException failure) {
        if (resolved.enabled()) {
            resolved.diagnostic().fail(failure);
        }
    }

    private void failIfEnabled(
            final ResolvedRedisCache resolved,
            final ProductionAdapterException failure) {
        if (resolved.enabled()) {
            resolved.diagnostic().fail(failure);
        }
    }

    private boolean allowedKafkaClientProperty(final String key) {
        if (ALLOWED_KAFKA_CLIENT_PROPERTY_KEYS.contains(key)) {
            return true;
        }
        for (String prefix : ALLOWED_KAFKA_CLIENT_PROPERTY_PREFIXES) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    private ResolvedProductionSetting directSetting(
            final ProductionConfigResolver resolver,
            final String adapterName,
            final String key,
            final boolean sensitive) {
        return resolver.read(
                adapterName,
                key,
                sensitive,
                List.of(key),
                List.of(key),
                List.of());
    }

    private int nacosRequestTimeout(
            final ProductionConfigResolver resolver,
            final NacosDiscoverySettings baseSettings,
            final ProductionStartupBudget startupBudget) {
        ResolvedProductionSetting configured = resolver.read(
                ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS,
                false,
                List.of(NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS),
                List.of(NacosDiscoveryConfigKeys.SYSTEM_REQUEST_TIMEOUT_MILLIS),
                List.of(NacosDiscoveryConfigKeys.ENV_REQUEST_TIMEOUT_MILLIS));
        int maximum = boundedMillis(startupBudget.adapterBudget());
        if (configured.present() && baseSettings.requestTimeoutMillis() > maximum) {
            throw ProductionAdapterFailures.invalidConfig(
                    ADAPTER_NACOS_DISCOVERY,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS);
        }
        return Math.min(baseSettings.requestTimeoutMillis(), maximum);
    }

    private int boundedMillis(final Duration timeout) {
        long millis = Math.max(1L, Objects.requireNonNull(timeout, "timeout").toMillis());
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private ResolvedAdapters resolveAdapters() {
        ProductionConfigResolver resolver = new ProductionConfigResolver(
                config,
                systemPropertyLookup,
                environmentLookup);
        ProductionStartupBudget startupBudget = resolveStartupBudget(resolver);
        List<ProductionAdapterDiagnostic> diagnostics = new ArrayList<>();
        ResolvedKafka kafka = resolveKafka(resolver, startupBudget, diagnostics);
        ResolvedMongo mongo = resolveMongo(resolver, diagnostics);
        ResolvedRedis redis = resolveRedis(resolver, diagnostics);
        ResolvedRedisData redisData = resolveRedisData(resolver, redis, diagnostics);
        ResolvedRedisCache redisCache = resolveRedisCache(resolver, redis, diagnostics);
        ResolvedPostgresql postgresql = resolvePostgresql(resolver, startupBudget, diagnostics);
        ResolvedNacos nacos = resolveNacos(resolver, startupBudget, diagnostics);
        return new ResolvedAdapters(
                kafka,
                mongo,
                redis,
                redisData,
                redisCache,
                postgresql,
                nacos,
                startupBudget,
                List.copyOf(diagnostics));
    }

    private ProductionStartupBudget resolveStartupBudget(final ProductionConfigResolver resolver) {
        int totalMillis = resolver.strictPositiveInt(
                "production-runtime",
                ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_BUDGET_MILLIS,
                ZeroProductionRuntimeConfigKeys.DEFAULT_ADAPTER_STARTUP_BUDGET_MILLIS);
        int adapterMillis = resolver.strictPositiveInt(
                "production-runtime",
                ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS,
                ZeroProductionRuntimeConfigKeys.DEFAULT_ADAPTER_STARTUP_TIMEOUT_MILLIS);
        if (adapterMillis > totalMillis) {
            throw ProductionAdapterFailures.invalidConfig(
                    "production-runtime",
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS);
        }
        return new ProductionStartupBudget(
                Duration.ofMillis(totalMillis),
                Duration.ofMillis(adapterMillis));
    }

    private ResolvedKafka resolveKafka(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        if (!resolver.strictEnabled(
                ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED)) {
            diagnostics.add(disabled(ADAPTER_KAFKA_RPC));
            return ResolvedKafka.disabled();
        }
        ResolvedProductionSetting bootstrap = resolver.read(
                ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
                true,
                List.of(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS),
                List.of(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS),
                List.of(ZeroProductionRuntimeConfigKeys.ENV_RPC_KAFKA_BOOTSTRAP_SERVERS));
        ResolvedProductionSetting clientId = directSetting(
                resolver, ADAPTER_KAFKA_RPC, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, true);
        ResolvedProductionSetting consumerGroup = directSetting(
                resolver, ADAPTER_KAFKA_RPC, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, true);
        ResolvedProductionSetting topicPrefix = directSetting(
                resolver, ADAPTER_KAFKA_RPC, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, true);
        ResolvedProductionSetting replyTopic = directSetting(
                resolver, ADAPTER_KAFKA_RPC, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, true);
        List<ResolvedProductionSetting> settings =
                List.of(bootstrap, clientId, consumerGroup, topicPrefix, replyTopic);
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ADAPTER_KAFKA_RPC,
                List.of(
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC),
                settings,
                List.of(
                        KafkaRpcLifecycleAdapter.class.getName(),
                        KafkaRpcSettings.class.getName()));
        diagnostics.add(diagnostic);
        if (settings.stream().anyMatch(setting -> !setting.present())) {
            return ResolvedKafka.missing(diagnostic);
        }
        return new ResolvedKafka(
                true,
                kafkaSettings(
                        bootstrap.require(),
                        clientId.require(),
                        consumerGroup.require(),
                        topicPrefix.require(),
                        replyTopic.require(),
                        resolver,
                        startupBudget),
                diagnostic);
    }

    private KafkaRpcSettings kafkaSettings(
            final String bootstrapServers,
            final String clientId,
            final String group,
            final String topicPrefix,
            final String replyTopic,
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget) {
        int pendingCapacity = resolver.strictPositiveInt(
                ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_PENDING_CAPACITY,
                4096);
        int pollMillis = resolver.strictPositiveInt(
                ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_POLL_TIMEOUT_MILLIS,
                100);
        int closeMillis = resolver.strictPositiveInt(
                ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLOSE_TIMEOUT_MILLIS,
                3000);
        int startupTimeoutMillis = boundedMillis(startupBudget.adapterBudget());
        Map<String, Object> producerProperties = new LinkedHashMap<>(kafkaClientProperties);
        producerProperties.put("request.timeout.ms", startupTimeoutMillis);
        producerProperties.put("max.block.ms", startupTimeoutMillis);
        Map<String, Object> consumerProperties = new LinkedHashMap<>(kafkaClientProperties);
        consumerProperties.put("request.timeout.ms", startupTimeoutMillis);
        consumerProperties.put("default.api.timeout.ms", startupTimeoutMillis);
        return new KafkaRpcSettings(
                bootstrapServers,
                clientId,
                group,
                topicPrefix,
                replyTopic,
                pendingCapacity,
                Duration.ofMillis(pollMillis),
                Duration.ofMillis(closeMillis),
                producerProperties,
                consumerProperties);
    }

    private ResolvedMongo resolveMongo(
            final ProductionConfigResolver resolver,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        if (!resolver.strictEnabled(
                ADAPTER_MONGO_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED)) {
            diagnostics.add(disabled(ADAPTER_MONGO_DATA));
            return ResolvedMongo.disabled();
        }
        ResolvedProductionSetting uri = resolver.read(
                ADAPTER_MONGO_DATA,
                MongoDriverSettings.PROPERTY_MONGO_URI,
                true,
                List.of(MongoDriverSettings.PROPERTY_MONGO_URI),
                List.of(MongoDriverSettings.PROPERTY_MONGO_URI),
                List.of(MongoDriverSettings.ENV_MONGO_URI));
        ResolvedProductionSetting database = resolver.read(
                ADAPTER_MONGO_DATA,
                MongoDriverSettings.PROPERTY_MONGO_DATABASE,
                true,
                List.of(MongoDriverSettings.PROPERTY_MONGO_DATABASE),
                List.of(MongoDriverSettings.PROPERTY_MONGO_DATABASE),
                List.of(MongoDriverSettings.ENV_MONGO_DATABASE));
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ADAPTER_MONGO_DATA,
                List.of(MongoDriverSettings.PROPERTY_MONGO_URI, MongoDriverSettings.PROPERTY_MONGO_DATABASE),
                List.of(uri, database),
                List.of(MongoDataAdapter.class.getName(), MongoClient.class.getName()));
        diagnostics.add(diagnostic);
        if (!uri.present() || !database.present()) {
            return ResolvedMongo.missing(diagnostic);
        }
        return new ResolvedMongo(true, new MongoDriverSettings(uri.require(), database.require()), diagnostic);
    }

    private ResolvedRedis resolveRedis(
            final ProductionConfigResolver resolver,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        boolean dataEnabled = resolver.strictEnabled(
                ADAPTER_REDIS_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED);
        boolean cacheEnabled = resolver.strictEnabled(
                ADAPTER_REDIS_CACHE,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED);
        boolean enabled = dataEnabled || cacheEnabled;
        if (!enabled) {
            return ResolvedRedis.disabled();
        }
        String owner = dataEnabled ? ADAPTER_REDIS_DATA : ADAPTER_REDIS_CACHE;
        ResolvedProductionSetting uri = redisUri(resolver, owner);
        if (!uri.present()) {
            return ResolvedRedis.missing(uri);
        }
        return new ResolvedRedis(true, new RedisDriverSettings(uri.require()), uri);
    }

    private ResolvedRedisData resolveRedisData(
            final ProductionConfigResolver resolver,
            final ResolvedRedis redis,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        if (!resolver.strictEnabled(
                ADAPTER_REDIS_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED)) {
            diagnostics.add(disabled(ADAPTER_REDIS_DATA));
            return ResolvedRedisData.disabled();
        }
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ADAPTER_REDIS_DATA,
                List.of(RedisDriverSettings.PROPERTY_REDIS_URI),
                List.of(redis.uri()),
                List.of(RedisDataAdapter.class.getName(), RedisClient.class.getName()));
        diagnostics.add(diagnostic);
        return redis.enabled() ? new ResolvedRedisData(true, diagnostic) : ResolvedRedisData.missing(diagnostic);
    }

    private ResolvedRedisCache resolveRedisCache(
            final ProductionConfigResolver resolver,
            final ResolvedRedis redis,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        if (!resolver.strictEnabled(
                ADAPTER_REDIS_CACHE,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED)) {
            diagnostics.add(disabled(ADAPTER_REDIS_CACHE));
            return ResolvedRedisCache.disabled();
        }
        List<ResolvedProductionSetting> settings = new ArrayList<>();
        settings.add(redis.uri());
        ResolvedProductionSetting namespace = directSetting(
                resolver,
                ADAPTER_REDIS_CACHE,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE,
                true);
        ResolvedProductionSetting cacheName = directSetting(
                resolver,
                ADAPTER_REDIS_CACHE,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                true);
        settings.add(namespace);
        settings.add(cacheName);
        if (redisCacheValueCodec != null) {
            settings.add(new ResolvedProductionSetting(
                    "builder.redisCacheValueCodec",
                    java.util.Optional.of(redisCacheValueCodec.name()),
                    java.util.Optional.of(new ZeroProductionConfigSource(
                            "builder.redisCacheValueCodec",
                            "builder",
                            "redisCacheValueCodec",
                            false))));
        } else {
            settings.add(new ResolvedProductionSetting(
                    "builder.redisCacheValueCodec",
                    java.util.Optional.empty(),
                    java.util.Optional.empty()));
        }
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ADAPTER_REDIS_CACHE,
                List.of(
                        RedisDriverSettings.PROPERTY_REDIS_URI,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                        "builder.redisCacheValueCodec"),
                settings,
                List.of(RedisDistributedCacheService.class.getName(), RedisCacheStore.class.getName()));
        diagnostics.add(diagnostic);
        boolean ready = redis.enabled()
                && namespace.present()
                && cacheName.present()
                && redisCacheValueCodec != null;
        return ready
                ? new ResolvedRedisCache(true, namespace.require(), cacheName.require(), diagnostic)
                : ResolvedRedisCache.missing(diagnostic);
    }

    private ResolvedProductionSetting redisUri(
            final ProductionConfigResolver resolver,
            final String adapterName) {
        return resolver.read(
                adapterName,
                RedisDriverSettings.PROPERTY_REDIS_URI,
                true,
                List.of(RedisDriverSettings.PROPERTY_REDIS_URI),
                List.of(RedisDriverSettings.PROPERTY_REDIS_URI),
                List.of(RedisDriverSettings.ENV_REDIS_URI));
    }

    private ResolvedPostgresql resolvePostgresql(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        if (!resolver.strictEnabled(
                ADAPTER_POSTGRESQL_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED)) {
            diagnostics.add(disabled(ADAPTER_POSTGRESQL_DATA));
            return ResolvedPostgresql.disabled();
        }
        if (startupBudget.adapterBudget().compareTo(Duration.ofSeconds(1)) < 0) {
            throw ProductionAdapterFailures.invalidConfig(
                    ADAPTER_POSTGRESQL_DATA,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS);
        }
        ResolvedProductionSetting jdbcUrl = resolver.read(
                ADAPTER_POSTGRESQL_DATA,
                PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                true,
                List.of(PostgresqlDriverSettings.JDBC_URL_PROPERTY),
                List.of(PostgresqlDriverSettings.JDBC_URL_PROPERTY),
                List.of(PostgresqlDriverSettings.JDBC_URL_ENV));
        ResolvedProductionSetting username = resolver.read(
                ADAPTER_POSTGRESQL_DATA,
                PostgresqlDriverSettings.USERNAME_PROPERTY,
                true,
                List.of(PostgresqlDriverSettings.USERNAME_PROPERTY),
                List.of(PostgresqlDriverSettings.USERNAME_PROPERTY),
                List.of(PostgresqlDriverSettings.USERNAME_ENV));
        ResolvedProductionSetting password = resolver.read(
                ADAPTER_POSTGRESQL_DATA,
                PostgresqlDriverSettings.PASSWORD_PROPERTY,
                true,
                List.of(PostgresqlDriverSettings.PASSWORD_PROPERTY),
                List.of(PostgresqlDriverSettings.PASSWORD_PROPERTY),
                List.of(PostgresqlDriverSettings.PASSWORD_ENV));
        ResolvedProductionSetting table = resolver.read(
                ADAPTER_POSTGRESQL_DATA,
                PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
                true,
                List.of(PostgresqlDriverSettings.TABLE_NAME_PROPERTY),
                List.of(PostgresqlDriverSettings.TABLE_NAME_PROPERTY),
                List.of(PostgresqlDriverSettings.TABLE_NAME_ENV));
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ADAPTER_POSTGRESQL_DATA,
                List.of(
                        PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                        PostgresqlDriverSettings.USERNAME_PROPERTY,
                        PostgresqlDriverSettings.PASSWORD_PROPERTY,
                        PostgresqlDriverSettings.TABLE_NAME_PROPERTY),
                List.of(jdbcUrl, username, password, table),
                List.of(PostgresqlDataAdapter.class.getName(), PostgresqlDriverSettings.class.getName()));
        diagnostics.add(diagnostic);
        if (!jdbcUrl.present() || !username.present() || !password.present() || !table.present()) {
            return ResolvedPostgresql.missing(diagnostic);
        }
        return new ResolvedPostgresql(
                true,
                new PostgresqlDriverSettings(
                        jdbcUrl.require(), username.require(), password.require(), table.require()),
                diagnostic);
    }

    private ResolvedNacos resolveNacos(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        String mode = resolver.strictChoice(
                ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.DISCOVERY_MODE,
                NacosDiscoveryConfigKeys.MODE_LOCAL,
                List.of(NacosDiscoveryConfigKeys.MODE_LOCAL, NacosDiscoveryConfigKeys.MODE_NACOS));
        if (NacosDiscoveryConfigKeys.MODE_LOCAL.equals(mode)) {
            diagnostics.add(disabled(ADAPTER_NACOS_DISCOVERY));
            return ResolvedNacos.disabled();
        }
        ResolvedProductionSetting server = resolver.read(
                ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.SERVER_ADDR,
                true,
                List.of(NacosDiscoveryConfigKeys.SERVER_ADDR),
                List.of(NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR),
                List.of(NacosDiscoveryConfigKeys.ENV_SERVER_ADDR));
        ResolvedProductionSetting namespace = resolver.read(
                ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.NAMESPACE,
                true,
                List.of(NacosDiscoveryConfigKeys.NAMESPACE),
                List.of(NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE),
                List.of(NacosDiscoveryConfigKeys.ENV_NAMESPACE));
        ResolvedProductionSetting group = resolver.read(
                ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                true,
                List.of(NacosDiscoveryConfigKeys.DEFAULT_GROUP),
                List.of(NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_GROUP),
                List.of(NacosDiscoveryConfigKeys.ENV_DEFAULT_GROUP));
        ResolvedProductionSetting cluster = resolver.read(
                ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.DEFAULT_CLUSTER,
                true,
                List.of(NacosDiscoveryConfigKeys.DEFAULT_CLUSTER),
                List.of(NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_CLUSTER),
                List.of(NacosDiscoveryConfigKeys.ENV_DEFAULT_CLUSTER));
        List<ResolvedProductionSetting> settings = List.of(server, namespace, group, cluster);
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ADAPTER_NACOS_DISCOVERY,
                List.of(
                        NacosDiscoveryConfigKeys.SERVER_ADDR,
                        NacosDiscoveryConfigKeys.NAMESPACE,
                        NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                        NacosDiscoveryConfigKeys.DEFAULT_CLUSTER),
                settings,
                List.of(ServiceDiscovery.class.getName()));
        diagnostics.add(diagnostic);
        if (settings.stream().anyMatch(setting -> !setting.present())) {
            return ResolvedNacos.missing(diagnostic);
        }
        NacosDiscoverySettings baseSettings = NacosDiscoverySettings.fromConfig(
                config,
                systemPropertyLookup,
                environmentLookup);
        int requestTimeoutMillis = nacosRequestTimeout(resolver, baseSettings, startupBudget);
        NacosDiscoverySettings resolvedSettings = new NacosDiscoverySettings(
                server.require(),
                namespace.require(),
                baseSettings.username(),
                baseSettings.password(),
                baseSettings.accessKey(),
                baseSettings.secretKey(),
                group.require(),
                cluster.require(),
                requestTimeoutMillis,
                baseSettings.namingLoadCacheAtStart(),
                baseSettings.healthUpdateMode(),
                baseSettings.extraProperties());
        return new ResolvedNacos(true, resolvedSettings, diagnostic);
    }

    private ProductionAdapterDiagnostic diagnostic(
            final String adapterName,
            final List<String> requiredKeys,
            final List<ResolvedProductionSetting> settings,
            final List<String> componentTypes) {
        List<String> configured = settings.stream()
                .filter(ResolvedProductionSetting::present)
                .map(ResolvedProductionSetting::logicalKey)
                .sorted()
                .toList();
        List<String> missing = settings.stream()
                .filter(setting -> !setting.present())
                .map(ResolvedProductionSetting::logicalKey)
                .sorted()
                .toList();
        List<ZeroProductionConfigSource> sources = settings.stream()
                .flatMap(setting -> setting.source().stream())
                .toList();
        return new ProductionAdapterDiagnostic(
                adapterName,
                missing.isEmpty()
                        ? ZeroProductionAdapterState.ENABLED
                        : ZeroProductionAdapterState.MISSING_CONFIG,
                requiredKeys,
                configured,
                missing,
                sources,
                componentTypes);
    }

    private ProductionAdapterDiagnostic disabled(final String adapterName) {
        return new ProductionAdapterDiagnostic(
                adapterName,
                ZeroProductionAdapterState.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private ZeroProductionAssemblyReport reportFromDiagnostics(
            final List<ProductionAdapterDiagnostic> diagnostics,
            final group.zn.zero.starter.ZeroRuntimeAssemblyReport runtimeReport,
            final List<String> lifecycleTypes) {
        Map<String, ZeroProductionAdapterStatus> statuses = new LinkedHashMap<>();
        for (ProductionAdapterDiagnostic diagnostic : diagnostics) {
            ZeroProductionAdapterStatus status = diagnostic.snapshot();
            statuses.put(status.adapterName(), status);
        }
        return new ZeroProductionAssemblyReport(
                profile,
                config.getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, ZeroRuntimeConfigKeys.DEFAULT_NAME),
                runtimeReport,
                statuses,
                lifecycleTypes,
                List.of());
    }

    private void failIfMissing(final ResolvedAdapters resolved) {
        List<String> missing = resolved.diagnostics().stream()
                .flatMap(diagnostic -> diagnostic.snapshot().missingConfigKeys().stream())
                .distinct()
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw ProductionAdapterFailures.missingConfig(missing);
        }
    }

    /**
     * MongoDB 构建阶段产生的 Adapter 与驱动客户端。
     *
     * @param adapter MongoDB Adapter；禁用时为空。
     * @param client MongoDB 驱动客户端；禁用时为空。
     * @author zn
     */
    private record MongoBuildResources(MongoDataAdapter adapter, MongoClient client) {

        /**
         * 返回未启用 MongoDB 时的空资源集合。
         *
         * @return 空资源集合；不可为空。
         */
        static MongoBuildResources disabled() {
            return new MongoBuildResources(null, null);
        }
    }

    /**
     * Redis 构建阶段产生的 data Adapter 与共享客户端。
     *
     * @param adapter Redis data Adapter；未启用 data 能力时为空。
     * @param client Redis 共享客户端；Redis 整体禁用时为空。
     * @author zn
     */
    private record RedisBuildResources(RedisDataAdapter adapter, RedisClient client) {

        /**
         * 返回未启用 Redis 时的空资源集合。
         *
         * @return 空资源集合；不可为空。
         */
        static RedisBuildResources disabled() {
            return new RedisBuildResources(null, null);
        }
    }

    /**
     * Nacos 构建阶段产生的服务发现与 RPC resolver。
     *
     * @param discovery 服务发现实例；禁用时为空。
     * @param rpcServiceResolver RPC 服务解析器；禁用时为空。
     * @author zn
     */
    private record NacosBuildResources(
            ServiceDiscovery discovery,
            RpcServiceResolver rpcServiceResolver) {

        /**
         * 返回未启用 Nacos 时的空资源集合。
         *
         * @return 空资源集合；不可为空。
         */
        static NacosBuildResources disabled() {
            return new NacosBuildResources(null, null);
        }
    }

    /**
     * 构建产物集合。
     *
     * @param components runtime 组件。
     * @param closeables 构建后可关闭资源。
     * @param adapters Adapter 实例。
     * @author zn
     */
    private record BuildAssembly(
            ZeroRuntimeComponents components,
            List<AutoCloseable> closeables,
            ZeroProductionRuntime.RuntimeAdapters adapters) {
    }

    /**
     * 已解析 Adapter 集合。
     *
     * @param kafka Kafka 解析结果。
     * @param mongo MongoDB 解析结果。
     * @param redis Redis 共用解析结果。
     * @param redisData Redis data 解析结果。
     * @param redisCache Redis cache 解析结果。
     * @param postgresql PostgreSQL 解析结果。
     * @param nacos Nacos 解析结果。
     * @param startupBudget 共享累计启动预算。
     * @param diagnostics 诊断状态。
     * @author zn
     */
    private record ResolvedAdapters(
            ResolvedKafka kafka,
            ResolvedMongo mongo,
            ResolvedRedis redis,
            ResolvedRedisData redisData,
            ResolvedRedisCache redisCache,
            ResolvedPostgresql postgresql,
            ResolvedNacos nacos,
            ProductionStartupBudget startupBudget,
            List<ProductionAdapterDiagnostic> diagnostics) {
    }

    /**
     * Kafka 解析结果。
     *
     * @param enabled 是否启用。
     * @param settings Kafka 配置。
     * @param diagnostic 诊断状态。
     * @author zn
     */
    private record ResolvedKafka(
            boolean enabled,
            KafkaRpcSettings settings,
            ProductionAdapterDiagnostic diagnostic) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedKafka disabled() {
            return new ResolvedKafka(false, null, null);
        }

        /**
         * 返回缺失配置结果。
         *
         * @param diagnostic 诊断状态；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedKafka missing(final ProductionAdapterDiagnostic diagnostic) {
            return new ResolvedKafka(false, null, diagnostic);
        }
    }

    /**
     * MongoDB 解析结果。
     *
     * @param enabled 是否启用。
     * @param settings MongoDB 配置。
     * @param diagnostic 诊断状态。
     * @author zn
     */
    private record ResolvedMongo(
            boolean enabled,
            MongoDriverSettings settings,
            ProductionAdapterDiagnostic diagnostic) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedMongo disabled() {
            return new ResolvedMongo(false, null, null);
        }

        /**
         * 返回缺失配置结果。
         *
         * @param diagnostic 诊断状态；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedMongo missing(final ProductionAdapterDiagnostic diagnostic) {
            return new ResolvedMongo(false, null, diagnostic);
        }
    }

    /**
     * Redis 共用解析结果。
     *
     * @param enabled 是否至少有一个 Redis adapter 启用。
     * @param settings Redis 配置。
     * @param uri Redis URI 配置来源。
     * @author zn
     */
    private record ResolvedRedis(boolean enabled, RedisDriverSettings settings, ResolvedProductionSetting uri) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedRedis disabled() {
            return new ResolvedRedis(false, null, new ResolvedProductionSetting(
                    RedisDriverSettings.PROPERTY_REDIS_URI,
                    java.util.Optional.empty(),
                    java.util.Optional.empty()));
        }

        /**
         * 返回缺失配置结果。
         *
         * @param uri Redis URI 配置来源；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedRedis missing(final ResolvedProductionSetting uri) {
            return new ResolvedRedis(false, null, uri);
        }
    }

    /**
     * Redis data 解析结果。
     *
     * @param enabled 是否启用。
     * @param diagnostic 诊断状态。
     * @author zn
     */
    private record ResolvedRedisData(boolean enabled, ProductionAdapterDiagnostic diagnostic) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedRedisData disabled() {
            return new ResolvedRedisData(false, null);
        }

        /**
         * 返回缺失配置结果。
         *
         * @param diagnostic 诊断状态；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedRedisData missing(final ProductionAdapterDiagnostic diagnostic) {
            return new ResolvedRedisData(false, diagnostic);
        }
    }

    /**
     * Redis cache 解析结果。
     *
     * @param enabled 是否启用。
     * @param namespace cache 命名空间。
     * @param cacheName cache 名称。
     * @param diagnostic 诊断状态。
     * @author zn
     */
    private record ResolvedRedisCache(
            boolean enabled,
            String namespace,
            String cacheName,
            ProductionAdapterDiagnostic diagnostic) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedRedisCache disabled() {
            return new ResolvedRedisCache(false, null, null, null);
        }

        /**
         * 返回缺失配置结果。
         *
         * @param diagnostic 诊断状态；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedRedisCache missing(final ProductionAdapterDiagnostic diagnostic) {
            return new ResolvedRedisCache(false, null, null, diagnostic);
        }
    }

    /**
     * PostgreSQL 解析结果。
     *
     * @param enabled 是否启用。
     * @param settings PostgreSQL 配置。
     * @param diagnostic 诊断状态。
     * @author zn
     */
    private record ResolvedPostgresql(
            boolean enabled,
            PostgresqlDriverSettings settings,
            ProductionAdapterDiagnostic diagnostic) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedPostgresql disabled() {
            return new ResolvedPostgresql(false, null, null);
        }

        /**
         * 返回缺失配置结果。
         *
         * @param diagnostic 诊断状态；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedPostgresql missing(final ProductionAdapterDiagnostic diagnostic) {
            return new ResolvedPostgresql(false, null, diagnostic);
        }
    }

    /**
     * Nacos 解析结果。
     *
     * @param enabled 是否启用。
     * @param settings Nacos 配置。
     * @param diagnostic 诊断状态。
     * @author zn
     */
    private record ResolvedNacos(
            boolean enabled,
            NacosDiscoverySettings settings,
            ProductionAdapterDiagnostic diagnostic) {

        /**
         * 返回禁用结果。
         *
         * @return 禁用结果；不可为空。
         */
        static ResolvedNacos disabled() {
            return new ResolvedNacos(false, null, null);
        }

        /**
         * 返回缺失配置结果。
         *
         * @param diagnostic 诊断状态；不可为空。
         * @return 缺失配置结果；不可为空。
         */
        static ResolvedNacos missing(final ProductionAdapterDiagnostic diagnostic) {
            return new ResolvedNacos(false, null, diagnostic);
        }
    }
}
