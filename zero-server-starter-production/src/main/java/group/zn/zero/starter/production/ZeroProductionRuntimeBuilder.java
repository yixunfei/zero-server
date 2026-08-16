package group.zn.zero.starter.production;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.log.LogSink;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.starter.LocalRuntime;
import group.zn.zero.starter.LocalRuntimeBuilder;
import group.zn.zero.starter.LocalRuntimePresets;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * production/external-test runtime 显式装配构建器。
 *
 * <p>该 builder 只在调用方明确选择 production 模块时创建真实 Adapter；默认 builder 不做 classpath
 * 自动发现，不会改变 {@code LocalRuntime} 行为。
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

    /** Production network lifecycle 组件名称。 */
    public static final String ADAPTER_NETWORK_LIFECYCLE = "network-lifecycle";

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

    /** 业务显式提供的握手、鉴权、心跳与重连策略。 */
    private ProductionNetworkPolicy networkPolicy;

    /** 可选自定义有界网络限流器。 */
    private NetworkRateLimiter networkRateLimiter;

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
        kafkaClientProperties = ProductionKafkaRpcProvider.validateClientProperties(properties);
        return this;
    }

    /**
     * 设置 production network 的业务策略；只有配置同时显式启用 network provider 时才生效。
     *
     * @param policy 握手、鉴权、心跳与重连策略；不可为空且必须线程安全。
     * @return 当前 builder；不可为空，非线程安全。
     */
    public ZeroProductionRuntimeBuilder networkPolicy(final ProductionNetworkPolicy policy) {
        networkPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * 覆盖 production network 默认的有界每 IP 限流器。
     *
     * <p>自定义实现必须低分配、有界且非阻塞；启用后默认限流配置保持未消费，延续旧手工装配入口语义。</p>
     *
     * @param rateLimiter 自定义限流器；不可为空且必须线程安全。
     * @return 当前 builder；不可为空，非线程安全。
     */
    public ZeroProductionRuntimeBuilder networkRateLimiter(final NetworkRateLimiter rateLimiter) {
        networkRateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
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
            GameRuntime runtime = buildRuntime(resolved);
            return new ZeroProductionRuntime(
                    profile,
                    runtime,
                    resolved.diagnostics());
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

    private GameRuntime buildRuntime(final ResolvedAdapters resolved) {
        try {
            LocalRuntimeBuilder assembly = LocalRuntime.builder(config, terminalLogSink, executors)
                    .preset(LocalRuntimePresets.local(), runtimeProfile())
                    .startupTimeout(resolved.startupBudget().totalBudget());
            if (resolved.kafka().enabled()) {
                configureKafka(assembly, resolved.kafka().provider());
            }
            if (resolved.mongo().enabled()) {
                configureMongo(assembly, resolved.mongo().provider());
            }
            if (resolved.postgresql().enabled()) {
                configurePostgresql(assembly, resolved.postgresql().provider());
            }
            if (resolved.redis().enabled()) {
                configureRedisResource(assembly, resolved.redis().resourceProvider());
            }
            if (resolved.redis().dataProvider() != null) {
                configureRedisData(assembly, resolved.redis().dataProvider());
            }
            if (resolved.redis().cacheProvider() != null) {
                configureRedisCache(assembly, resolved.redis().cacheProvider());
            }
            if (resolved.nacos().enabled()) {
                configureNacos(assembly, resolved.nacos());
            }
            if (resolved.network().enabled()) {
                configureNetwork(assembly, resolved.network().provider());
            }
            return assembly.build();
        } catch (RuntimeException | Error failure) {
            throw buildFailure(resolved.diagnostics(), failure);
        }
    }

    private void configureKafka(
            final LocalRuntimeBuilder assembly,
            final ProductionKafkaRpcProvider provider) {
        register(assembly, provider);
        assembly.override(ProductionRuntimeCapabilities.RPC_TRANSPORT, provider.descriptor().id())
                .override(ProductionRuntimeCapabilities.RPC_HANDLER_REGISTRY, provider.descriptor().id());
        provider.configSources().forEach(assembly::configSource);
    }

    private void configureMongo(
            final LocalRuntimeBuilder assembly,
            final ProductionMongoDataProvider provider) {
        register(assembly, provider);
        assembly.contribute(ProductionRuntimeCapabilities.DATA_SERVICES, provider.descriptor().id());
        provider.configSources().forEach(assembly::configSource);
    }

    private void configurePostgresql(
            final LocalRuntimeBuilder assembly,
            final ProductionPostgresqlDataProvider provider) {
        register(assembly, provider);
        assembly.contribute(ProductionRuntimeCapabilities.DATA_SERVICES, provider.descriptor().id());
        provider.configSources().forEach(assembly::configSource);
    }

    private void configureRedisResource(
            final LocalRuntimeBuilder assembly,
            final ProductionRedisResourceProvider provider) {
        register(assembly, provider);
        assembly.override(ProductionRedisRuntimeCapabilities.RESOURCE, provider.descriptor().id());
        provider.configSources().forEach(assembly::configSource);
    }

    private void configureRedisData(
            final LocalRuntimeBuilder assembly,
            final ProductionRedisDataProvider provider) {
        register(assembly, provider);
        assembly.contribute(ProductionRuntimeCapabilities.DATA_SERVICES, provider.descriptor().id());
    }

    private void configureRedisCache(
            final LocalRuntimeBuilder assembly,
            final ProductionRedisCacheProvider provider) {
        register(assembly, provider);
        assembly.override(ProductionRuntimeCapabilities.CACHE_SERVICE, provider.descriptor().id());
        provider.configSources().forEach(assembly::configSource);
    }

    private void configureNacos(
            final LocalRuntimeBuilder assembly,
            final ProductionNacosProviders.Resolution nacos) {
        ProductionNacosDiscoveryProvider discovery = nacos.discoveryProvider();
        register(assembly, discovery);
        assembly.override(ProductionRuntimeCapabilities.SERVICE_DISCOVERY, discovery.descriptor().id())
                .require(ProductionRuntimeCapabilities.SERVICE_DISCOVERY);
        discovery.configSources().forEach(assembly::configSource);
        ProductionNacosRpcResolverProvider resolver = nacos.resolverProvider();
        register(assembly, resolver);
        assembly.override(ProductionRuntimeCapabilities.RPC_SERVICE_RESOLVER, resolver.descriptor().id())
                .require(ProductionRuntimeCapabilities.RPC_SERVICE_RESOLVER);
    }

    private void configureNetwork(
            final LocalRuntimeBuilder assembly,
            final ProductionNetworkProvider provider) {
        register(assembly, provider);
        assembly.override(ProductionRuntimeCapabilities.NETWORK_LIFECYCLE, provider.descriptor().id())
                .require(ProductionRuntimeCapabilities.NETWORK_LIFECYCLE);
        provider.configSources().forEach(assembly::configSource);
    }

    private void register(
            final LocalRuntimeBuilder assembly,
            final RuntimeComponentProvider provider) {
        StandardRuntimeCapabilityModel.instance().validateDescriptor(provider.descriptor(), profile);
        assembly.register(provider);
    }

    private RuntimeProfile runtimeProfile() {
        return switch (profile) {
            case ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION -> RuntimeProfile.production(java.util.Set.of());
            case ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST -> RuntimeProfile.externalTest();
            default -> throw new IllegalArgumentException("unsupported production runtime profile");
        };
    }

    private ProductionAdapterException buildFailure(
            final List<ProductionAdapterDiagnostic> diagnostics,
            final Throwable failure) {
        for (ProductionAdapterDiagnostic diagnostic : diagnostics) {
            ZeroProductionAdapterStatus status = diagnostic.snapshot();
            if (status.state() == ZeroProductionAdapterState.FAILED
                    && status.failurePhase() == ProductionAdapterFailurePhase.CLIENT_CREATION
                    && status.errorCode() != null) {
                return ProductionAdapterFailures.sanitize(
                        status.adapterName(),
                        status.failurePhase(),
                        status.errorCode(),
                        status.message(),
                        failure);
            }
        }
        return ProductionAdapterFailures.sanitize(
                "production-runtime",
                ProductionAdapterFailurePhase.CLIENT_CREATION,
                ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                failure);
    }

    private ResolvedAdapters resolveAdapters() {
        ProductionConfigResolver resolver = new ProductionConfigResolver(
                config,
                systemPropertyLookup,
                environmentLookup);
        ProductionStartupBudget startupBudget = resolveStartupBudget(resolver);
        List<ProductionAdapterDiagnostic> diagnostics = new ArrayList<>();
        ProductionKafkaRpcProvider.Resolution kafka = ProductionKafkaRpcProvider.resolve(
                resolver, startupBudget, kafkaClientProperties);
        diagnostics.add(kafka.diagnostic());
        ProductionMongoDataProvider.Resolution mongo = ProductionMongoDataProvider.resolve(
                resolver, startupBudget);
        diagnostics.add(mongo.diagnostic());
        ProductionPostgresqlDataProvider.Resolution postgresql = ProductionPostgresqlDataProvider.resolve(
                resolver, startupBudget);
        diagnostics.add(postgresql.diagnostic());
        ProductionRedisProviders.Resolution redis = ProductionRedisProviders.resolve(
                resolver, startupBudget, redisCacheValueCodec);
        diagnostics.addAll(redis.diagnostics());
        ProductionNacosProviders.Resolution nacos = ProductionNacosProviders.resolve(
                resolver,
                startupBudget,
                config,
                systemPropertyLookup,
                environmentLookup);
        diagnostics.add(nacos.diagnostic());
        ProductionNetworkProvider.Resolution network = ProductionNetworkProvider.resolve(
                resolver, networkPolicy, networkRateLimiter, executors);
        return new ResolvedAdapters(
                kafka,
                mongo,
                redis,
                postgresql,
                nacos,
                network,
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

    private ZeroProductionAssemblyReport reportFromDiagnostics(
            final List<ProductionAdapterDiagnostic> diagnostics,
            final RuntimeAssemblyReport runtimeReport,
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
     * 已解析 Adapter 集合。
     *
     * @param kafka Kafka 解析结果。
     * @param mongo MongoDB 解析结果。
     * @param redis Redis 共用解析结果。
     * @param redisData Redis data 解析结果。
     * @param redisCache Redis cache 解析结果。
     * @param postgresql PostgreSQL 解析结果。
     * @param nacos Nacos 解析结果。
     * @param network production network 解析结果。
     * @param startupBudget 共享累计启动预算。
     * @param diagnostics 诊断状态。
     * @author zn
     */
    private record ResolvedAdapters(
            ProductionKafkaRpcProvider.Resolution kafka,
            ProductionMongoDataProvider.Resolution mongo,
            ProductionRedisProviders.Resolution redis,
            ProductionPostgresqlDataProvider.Resolution postgresql,
            ProductionNacosProviders.Resolution nacos,
            ProductionNetworkProvider.Resolution network,
            ProductionStartupBudget startupBudget,
            List<ProductionAdapterDiagnostic> diagnostics) {
    }

}
