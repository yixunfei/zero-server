package group.zn.zero.starter;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.log.LogSink;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.RuntimeLifecycleCapabilities;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.cache.CacheRuntime;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.event.EventRuntime;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.protocol.ProtocolRuntime;
import group.zn.zero.runtime.rpc.RpcRuntime;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Convenience catalog assembled from independently consumable integration modules. */
final class LocalRuntimeProviders {
    private LocalRuntimeProviders() {
    }

    static List<RuntimeComponentProvider> defaults(
            final ZeroConfig config, final Supplier<? extends LogSink> sink, final ZeroRuntimeExecutors executors) {
        List<RuntimeComponentProvider> providers = new ArrayList<>(RuntimeBasics.providers(config, executors));
        providers.addAll(LogRuntime.providers(sink));
        providers.addAll(EventRuntime.providers());
        providers.addAll(ActorRuntime.providers());
        providers.addAll(ProtocolRuntime.providers());
        providers.addAll(RpcRuntime.providers());
        providers.addAll(DataRuntime.providers());
        providers.addAll(CacheRuntime.providers());
        providers.addAll(MonitorRuntimeComponent.providers());
        return List.copyOf(providers);
    }

    static <T> RuntimeComponentProvider value(
            final ComponentId id, final ComponentKey<T> key, final T value, final ComponentKind kind) {
        return RuntimeProviders.value(id, key, value, kind);
    }

    static RuntimeComponentProvider infrastructureLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(componentId)
                .provide(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES)
                .kind(ComponentKind.BUSINESS)
                .build();
        return RuntimeProviders.create(descriptor, context -> ComponentContribution.builder()
                .contribute(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES, lifecycle)
                .lifecycle(lifecycle)
                .build());
    }

    static RuntimeComponentProvider applicationLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(componentId)
                .provide(RuntimeLifecycleCapabilities.APPLICATION_LIFECYCLES)
                .require(DataRuntime.PERSISTENCE_MANAGER)
                .kind(ComponentKind.BUSINESS)
                .build();
        return RuntimeProviders.create(descriptor, context -> {
            context.require(DataRuntime.PERSISTENCE_MANAGER);
            return ComponentContribution.builder()
                    .contribute(RuntimeLifecycleCapabilities.APPLICATION_LIFECYCLES, lifecycle)
                    .lifecycle(lifecycle)
                    .build();
        });
    }

}
