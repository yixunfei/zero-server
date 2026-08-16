package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.config.ResolvedRuntimeConfig;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import java.util.List;
import java.util.Objects;

/**
 * public plan 与实际 provider/config 引用的内部组合。
 *
 * @param plan 安全公开计划。
 * @param orderedProviders 拓扑顺序 provider。
 * @param config typed 配置快照。
 */
record PlannedRuntime(
        RuntimeAssemblyPlan plan,
        List<ComponentCatalog.RegisteredProvider> orderedProviders,
        ResolvedRuntimeConfig config) {

    PlannedRuntime {
        plan = Objects.requireNonNull(plan, "plan");
        orderedProviders = List.copyOf(Objects.requireNonNull(orderedProviders, "orderedProviders"));
        config = Objects.requireNonNull(config, "config");
    }
}
