package group.zn.zero.runtime.net;

import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.util.List;

/** Production connection lifecycle integration; policy and non-inline executors remain explicit. */
public final class NetworkRuntime {
    public static final ComponentKey<ProductionNetworkLifecycle> NETWORK_LIFECYCLE = ComponentKey.single(
            StandardRuntimeCapabilityModel.NETWORK_LIFECYCLE, ProductionNetworkLifecycle.class);

    private NetworkRuntime() {
    }

    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy, final NetworkRateLimiter limiter) {
        return module(policy, limiter, null);
    }

    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter limiter,
            final SecurityChain securityChain) {
        return context -> {
            ProductionNetworkProvider.Resolution resolved = ProductionNetworkProvider.resolve(
                    context.resolver(), policy, limiter, securityChain);
            return new ProductionModule(
                    resolved.enabled() ? List.of(resolved.provider()) : List.of(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(),
                    resolved.enabled() ? List.of(resolved.provider().diagnostic()) : List.of());
        };
    }
}
