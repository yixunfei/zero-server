package group.zn.zero.starter.production;

import group.zn.zero.cache.CacheKeyCodecs;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.data.redis.DefaultRedisCacheKeyStrategy;
import group.zn.zero.data.redis.RedisCacheEnvelopeCodec;
import group.zn.zero.data.redis.RedisCacheStore;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ComponentConfig;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Redis L2 cache 的显式 external runtime provider。 */
final class ProductionRedisCacheProvider implements RuntimeComponentProvider {

    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_REDIS_CACHE;

    private static final Set<ConfigSourceKind> SOURCES = Set.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY);
    private static final ConfigKey<String> NAMESPACE = sensitiveString(
            ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE);
    private static final ConfigKey<String> CACHE_NAME = sensitiveString(
            ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME);
    private static final ConfigSchema CONFIG_SCHEMA = ConfigSchema.builder(ID)
            .add(NAMESPACE)
            .add(CACHE_NAME)
            .build();

    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(ProductionRuntimeCapabilities.CACHE_SERVICE)
            .require(ProductionRedisRuntimeCapabilities.RESOURCE)
            .startAfter(ProductionRedisDataProvider.ID)
            .configSchema(CONFIG_SCHEMA)
            .kind(ComponentKind.EXTERNAL)
            .health(HealthPhase.STARTUP)
            .build();
    private final ProductionAdapterDiagnostic diagnostic;
    private final ProductionStartupBudget startupBudget;
    private final CacheValueCodec<Object> valueCodec;
    private final List<ConfigSource> configSources;

    ProductionRedisCacheProvider(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final CacheValueCodec<Object> valueCodec,
            final List<ConfigSource> configSources) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec");
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentCreationContext checked = Objects.requireNonNull(context, "context");
        try {
            ProductionRedisResource resource = checked.require(ProductionRedisRuntimeCapabilities.RESOURCE);
            CacheService<Object, Object> cacheService = cacheService(
                    resource.acquire(checked.resources()),
                    checked.config());
            diagnostic.mark(ZeroProductionAdapterState.CREATED);
            return ComponentContribution.builder()
                    .bind(ProductionRuntimeCapabilities.CACHE_SERVICE, cacheService)
                    .lifecycle(ProductionAdapterLifecycle.marker(diagnostic, startupBudget))
                    .healthProbe(HealthPhase.STARTUP, new ProductionStartupHealthProbe(
                            diagnostic,
                            timeout -> ProductionAdapterHealthProbes.redisCache(resource.settings(), timeout),
                            startupBudget))
                    .build();
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

    List<ConfigSource> configSources() {
        return configSources;
    }

    private CacheService<Object, Object> cacheService(
            final redis.clients.jedis.RedisClient client,
            final ComponentConfig config) {
        return new RedisDistributedCacheService<>(
                "redis",
                CachePolicy.defaults(),
                new RedisCacheStore<>(
                        client,
                        config.require(NAMESPACE),
                        config.require(CACHE_NAME),
                        1,
                        new DefaultRedisCacheKeyStrategy(),
                        CacheKeyCodecs.defaults(),
                        valueCodec,
                        new RedisCacheEnvelopeCodec()));
    }

    private static ConfigKey<String> sensitiveString(final String logicalName) {
        return ConfigKey.string(ID, logicalName)
                .acceptedSources(SOURCES)
                .validate(value -> !value.isBlank(), "non-blank")
                .sensitive()
                .build();
    }
}
