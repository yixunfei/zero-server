package group.zn.zero.runtime.production;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.function.Function;
import java.util.Objects;

/** Shared configuration sources and startup budget for explicitly installed integrations. */
public record ProductionContext(
        ZeroConfig config,
        ProductionConfigResolver resolver,
        ProductionStartupBudget startupBudget,
        ZeroRuntimeExecutors executors,
        Function<String, String> systemProperties,
        Function<String, String> environment) {
    public ProductionContext {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(startupBudget, "startupBudget");
        Objects.requireNonNull(executors, "executors");
        Objects.requireNonNull(systemProperties, "systemProperties");
        Objects.requireNonNull(environment, "environment");
    }

    @Override
    public String toString() {
        return "ProductionContext{configuration=redacted}";
    }
}
