package group.zn.zero.runtime.postgresql;

import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import java.util.List;

/** Independently installable postgresql integration; the existing enabled selector remains explicit. */
public final class PostgresqlRuntime {
    private PostgresqlRuntime() {
    }

    public static ProductionModuleFactory module() {
        return module(8);
    }

    /** Selects the maximum number of pooled physical connections owned by this runtime. */
    public static ProductionModuleFactory module(final int maximumConnections) {
        if (maximumConnections <= 0) { throw new IllegalArgumentException("maximumConnections must be positive"); }
        return context -> {
            ProductionPostgresqlDataProvider.Resolution resolved = ProductionPostgresqlDataProvider.resolve(
                    context.resolver(), context.startupBudget(), maximumConnections);
            return ProductionModule.single(resolved.provider(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(), resolved.diagnostic());
        };
    }
}
