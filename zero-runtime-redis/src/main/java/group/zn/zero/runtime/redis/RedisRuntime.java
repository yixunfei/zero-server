package group.zn.zero.runtime.redis;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.ArrayList;
import java.util.List;

/** Redis data/cache integration shares one managed client and has no dependency on other drivers. */
public final class RedisRuntime {
    private RedisRuntime() {
    }

    public static ProductionModuleFactory module() {
        return module(null);
    }

    public static ProductionModuleFactory module(final CacheValueCodec<Object> codec) {
        return context -> {
            ProductionRedisProviders.Resolution resolved = ProductionRedisProviders.resolve(
                    context.resolver(), context.startupBudget(), codec);
            List<RuntimeComponentProvider> providers = new ArrayList<>();
            List<ConfigSource> sources = new ArrayList<>();
            if (resolved.resourceProvider() != null) {
                providers.add(resolved.resourceProvider());
                sources.addAll(resolved.resourceProvider().configSources());
            }
            if (resolved.dataProvider() != null) {
                providers.add(resolved.dataProvider());
            }
            if (resolved.cacheProvider() != null) {
                providers.add(resolved.cacheProvider());
                sources.addAll(resolved.cacheProvider().configSources());
            }
            return new ProductionModule(providers, sources, resolved.diagnostics());
        };
    }
}
