package group.zn.zero.runtime.cache;

import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.LayeredCacheService;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Explicit cache bindings and lazy local providers. */
public final class CacheRuntime {

    public static final ComponentKey<CacheService<Object, Object>> CACHE_SERVICE = cacheKey();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ComponentKey<CacheService<Object, Object>> cacheKey() {
        return ComponentKey.single(StandardRuntimeCapabilityModel.CACHE_SERVICE, (Class) CacheService.class);
    }

    private CacheRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.cache", providers(), CACHE_SERVICE);
    }

    public static List<RuntimeComponentProvider> providers() {
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_CACHE)
                .provide(CACHE_SERVICE).kind(ComponentKind.LOCAL).build(), context ->
                ComponentContribution.builder().bind(CACHE_SERVICE, new LayeredCacheService<>(CachePolicy.defaults()))
                        .build()));
    }
}
