package group.zn.zero.runtime.nacos;

import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import java.util.List;

/** Explicit Nacos service discovery and RPC resolver integration. */
public final class NacosRuntime {
    private NacosRuntime() {
    }

    public static ProductionModuleFactory module() {
        return context -> {
            ProductionNacosProviders.Resolution resolved = ProductionNacosProviders.resolve(
                    context.resolver(), context.startupBudget(), context.config(),
                    context.systemProperties(), context.environment());
            if (!resolved.enabled()) {
                return new ProductionModule(List.of(), List.of(), List.of(resolved.diagnostic()));
            }
            return new ProductionModule(List.of(resolved.discoveryProvider(), resolved.resolverProvider()),
                    resolved.discoveryProvider().configSources(), List.of(resolved.diagnostic()));
        };
    }
}
