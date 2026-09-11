package group.zn.zero.runtime.kafka;

import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import java.util.List;
import java.util.Map;

/** Kafka RPC integration; requires an explicitly installed logging capability. */
public final class KafkaRuntime {
    private KafkaRuntime() {
    }

    public static ProductionModuleFactory module() {
        return module(Map.of());
    }

    public static ProductionModuleFactory module(final Map<String, ?> properties) {
        Map<String, Object> checked = ProductionKafkaRpcProvider.validateClientProperties(properties);
        return context -> {
            ProductionKafkaRpcProvider.Resolution resolved = ProductionKafkaRpcProvider.resolve(
                    context.resolver(), context.startupBudget(), checked);
            return ProductionModule.single(resolved.provider(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(), resolved.diagnostic());
        };
    }
}
