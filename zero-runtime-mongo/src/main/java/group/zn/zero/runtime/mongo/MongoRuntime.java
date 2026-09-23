package group.zn.zero.runtime.mongo;

import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import java.util.List;

/** Independently installable mongo integration; the existing enabled selector remains explicit. */
public final class MongoRuntime {
    private MongoRuntime() {
    }

    public static ProductionModuleFactory module() {
        return context -> {
            ProductionMongoDataProvider.Resolution resolved = ProductionMongoDataProvider.resolve(
                    context.resolver(), context.startupBudget());
            return ProductionModule.single(resolved.provider(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(), resolved.diagnostic());
        };
    }
}
