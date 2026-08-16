package group.zn.zero.starter.production;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.data.redis.RedisCacheStore;
import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import redis.clients.jedis.RedisClient;

/** Redis shared resource、data 与 cache provider 的原子选择和配置解析。 */
final class ProductionRedisProviders {

    private static final String CACHE_CODEC = "builder.redisCacheValueCodec";

    private ProductionRedisProviders() {
    }

    static Resolution resolve(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final CacheValueCodec<Object> cacheValueCodec) {
        ProductionConfigResolver checkedResolver = Objects.requireNonNull(resolver, "resolver");
        ProductionStartupBudget checkedBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        boolean dataSelected = checkedResolver.strictEnabled(
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED);
        boolean cacheSelected = checkedResolver.strictEnabled(
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED);
        ResolvedProductionSetting uri = dataSelected || cacheSelected
                ? redisUri(checkedResolver, dataSelected)
                : missing(RedisDriverSettings.PROPERTY_REDIS_URI);

        ProductionAdapterDiagnostic dataDiagnostic = dataDiagnostic(dataSelected, uri);
        CacheResolution cache = cacheResolution(
                checkedResolver, checkedBudget, cacheSelected, uri, cacheValueCodec);
        ProductionRedisDataProvider dataProvider = dataSelected && uri.present()
                ? new ProductionRedisDataProvider(dataDiagnostic, checkedBudget)
                : null;
        List<ProductionAdapterDiagnostic> enabledDiagnostics = new ArrayList<>();
        if (dataProvider != null) {
            enabledDiagnostics.add(dataDiagnostic);
        }
        if (cache.provider() != null) {
            enabledDiagnostics.add(cache.diagnostic());
        }
        ProductionRedisResourceProvider resourceProvider = enabledDiagnostics.isEmpty()
                ? null
                : new ProductionRedisResourceProvider(
                        checkedBudget,
                        dataProvider != null
                                ? ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA
                                : ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE,
                        enabledDiagnostics,
                        ProductionConfigSources.from(ProductionRedisResourceProvider.ID, List.of(uri)));
        return new Resolution(
                resourceProvider,
                dataProvider,
                cache.provider(),
                List.of(dataDiagnostic, cache.diagnostic()));
    }

    private static CacheResolution cacheResolution(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final boolean selected,
            final ResolvedProductionSetting uri,
            final CacheValueCodec<Object> valueCodec) {
        if (!selected) {
            return new CacheResolution(disabled(ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE), null);
        }
        ResolvedProductionSetting namespace = directSetting(
                resolver,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE,
                true);
        ResolvedProductionSetting cacheName = directSetting(
                resolver,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                true);
        ResolvedProductionSetting codec = codecSetting(valueCodec);
        List<ResolvedProductionSetting> settings = List.of(uri, namespace, cacheName, codec);
        ProductionAdapterDiagnostic diagnostic = diagnostic(
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE,
                List.of(
                        RedisDriverSettings.PROPERTY_REDIS_URI,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE,
                        ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                        CACHE_CODEC),
                settings,
                List.of(RedisDistributedCacheService.class.getName(), RedisCacheStore.class.getName()));
        if (settings.stream().anyMatch(setting -> !setting.present())) {
            return new CacheResolution(diagnostic, null);
        }
        ProductionRedisCacheProvider provider = new ProductionRedisCacheProvider(
                diagnostic,
                startupBudget,
                valueCodec,
                ProductionConfigSources.from(
                        ProductionRedisCacheProvider.ID,
                        List.of(namespace, cacheName)));
        return new CacheResolution(diagnostic, provider);
    }

    private static ProductionAdapterDiagnostic dataDiagnostic(
            final boolean selected,
            final ResolvedProductionSetting uri) {
        if (!selected) {
            return disabled(ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA);
        }
        return diagnostic(
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA,
                List.of(RedisDriverSettings.PROPERTY_REDIS_URI),
                List.of(uri),
                List.of(RedisDataAdapter.class.getName(), RedisClient.class.getName()));
    }

    private static ResolvedProductionSetting redisUri(
            final ProductionConfigResolver resolver,
            final boolean dataSelected) {
        String owner = dataSelected
                ? ZeroProductionRuntimeBuilder.ADAPTER_REDIS_DATA
                : ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE;
        return resolver.read(
                owner,
                RedisDriverSettings.PROPERTY_REDIS_URI,
                true,
                List.of(RedisDriverSettings.PROPERTY_REDIS_URI),
                List.of(RedisDriverSettings.PROPERTY_REDIS_URI),
                List.of(RedisDriverSettings.ENV_REDIS_URI));
    }

    private static ResolvedProductionSetting directSetting(
            final ProductionConfigResolver resolver,
            final String key,
            final boolean sensitive) {
        return resolver.read(
                ZeroProductionRuntimeBuilder.ADAPTER_REDIS_CACHE,
                key,
                sensitive,
                List.of(key),
                List.of(key),
                List.of());
    }

    private static ResolvedProductionSetting codecSetting(final CacheValueCodec<Object> valueCodec) {
        if (valueCodec == null) {
            return missing(CACHE_CODEC);
        }
        return new ResolvedProductionSetting(
                CACHE_CODEC,
                Optional.of(valueCodec.name()),
                Optional.of(new ZeroProductionConfigSource(
                        CACHE_CODEC,
                        "builder",
                        "redisCacheValueCodec",
                        false)));
    }

    private static ResolvedProductionSetting missing(final String key) {
        return new ResolvedProductionSetting(key, Optional.empty(), Optional.empty());
    }

    private static ProductionAdapterDiagnostic diagnostic(
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
        return new ProductionAdapterDiagnostic(
                adapterName,
                missing.isEmpty() ? ZeroProductionAdapterState.ENABLED : ZeroProductionAdapterState.MISSING_CONFIG,
                requiredKeys,
                configured,
                missing,
                settings.stream().flatMap(setting -> setting.source().stream()).toList(),
                componentTypes);
    }

    private static ProductionAdapterDiagnostic disabled(final String adapterName) {
        return new ProductionAdapterDiagnostic(
                adapterName,
                ZeroProductionAdapterState.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /** Redis provider family 的完整选择结果。 */
    record Resolution(
            ProductionRedisResourceProvider resourceProvider,
            ProductionRedisDataProvider dataProvider,
            ProductionRedisCacheProvider cacheProvider,
            List<ProductionAdapterDiagnostic> diagnostics) {

        Resolution {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if ((dataProvider != null || cacheProvider != null) != (resourceProvider != null)) {
                throw new IllegalArgumentException("selected Redis adapters require exactly one resource provider");
            }
        }

        boolean enabled() {
            return resourceProvider != null;
        }
    }

    private record CacheResolution(
            ProductionAdapterDiagnostic diagnostic,
            ProductionRedisCacheProvider provider) {

        CacheResolution {
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }
}
