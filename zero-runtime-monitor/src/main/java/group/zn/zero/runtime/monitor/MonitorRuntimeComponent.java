package group.zn.zero.runtime.monitor;

import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Explicit monitor bindings and lazy local providers. */
public final class MonitorRuntimeComponent {
    public static final ComponentKey<MonitorRuntime> MONITOR_RUNTIME = ComponentKey.single(
            StandardRuntimeCapabilityModel.MONITOR_RUNTIME, MonitorRuntime.class);

    private MonitorRuntimeComponent() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.monitor", providers(), MONITOR_RUNTIME);
    }

    public static List<RuntimeComponentProvider> providers() {
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_MONITOR)
                .provide(MONITOR_RUNTIME).kind(ComponentKind.LOCAL).build(), context ->
                ComponentContribution.builder().bind(MONITOR_RUNTIME, MonitorRuntime.createDefault()).build()));
    }
}
