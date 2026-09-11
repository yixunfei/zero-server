package group.zn.zero.runtime.discovery;

import group.zn.zero.discovery.InMemoryServiceDiscovery;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.rpc.discovery.ServiceDiscoveryRpcServiceResolver;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.rpc.RpcRuntime;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Middleware-neutral service discovery keys and a local registry/resolver module. */
public final class DiscoveryRuntime {
    public static final ComponentKey<ServiceDiscovery> SERVICE_DISCOVERY = ComponentKey.single(
            StandardRuntimeCapabilityModel.SERVICE_DISCOVERY, ServiceDiscovery.class);

    private DiscoveryRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.discovery", List.of(
                RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("zero.discovery.local"))
                        .provide(SERVICE_DISCOVERY).kind(ComponentKind.LOCAL).build(), context -> {
                            InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
                            return ComponentContribution.builder().bind(SERVICE_DISCOVERY, discovery)
                                    .lifecycle(discovery).build();
                        }),
                RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("zero.discovery.rpc-resolver"))
                        .provide(RpcRuntime.RPC_SERVICE_RESOLVER).require(SERVICE_DISCOVERY)
                        .kind(ComponentKind.LOCAL).build(), context -> ComponentContribution.builder()
                        .bind(RpcRuntime.RPC_SERVICE_RESOLVER,
                                new ServiceDiscoveryRpcServiceResolver(context.require(SERVICE_DISCOVERY))).build())),
                SERVICE_DISCOVERY, RpcRuntime.RPC_SERVICE_RESOLVER);
    }
}
