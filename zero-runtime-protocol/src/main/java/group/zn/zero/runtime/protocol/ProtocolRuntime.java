package group.zn.zero.runtime.protocol;

import group.zn.zero.protocol.registry.InMemoryProtocolRegistry;
import group.zn.zero.protocol.registry.ProtocolRegistry;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Explicit protocol bindings and lazy local providers. */
public final class ProtocolRuntime {
    public static final ComponentKey<ProtocolRegistry> PROTOCOL_REGISTRY = ComponentKey.single(
            StandardRuntimeCapabilityModel.PROTOCOL_REGISTRY, ProtocolRegistry.class);

    private ProtocolRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.protocol", providers(), PROTOCOL_REGISTRY);
    }

    public static List<RuntimeComponentProvider> providers() {
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_PROTOCOL)
                .provide(PROTOCOL_REGISTRY).kind(ComponentKind.LOCAL).build(), context ->
                ComponentContribution.builder().bind(PROTOCOL_REGISTRY, new InMemoryProtocolRegistry()).build()));
    }
}
