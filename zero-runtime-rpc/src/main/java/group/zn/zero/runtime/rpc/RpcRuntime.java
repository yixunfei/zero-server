package group.zn.zero.runtime.rpc;

import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Explicit rpc bindings and lazy local providers. */
public final class RpcRuntime {
    public static final ComponentKey<RpcTransport> RPC_TRANSPORT = ComponentKey.single(
            StandardRuntimeCapabilityModel.RPC_TRANSPORT, RpcTransport.class);
    public static final ComponentKey<RpcHandlerRegistry> RPC_HANDLER_REGISTRY = ComponentKey.single(
            StandardRuntimeCapabilityModel.RPC_HANDLER_REGISTRY, RpcHandlerRegistry.class);
    public static final ComponentKey<RpcServiceResolver> RPC_SERVICE_RESOLVER = ComponentKey.single(
            StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER, RpcServiceResolver.class);

    private RpcRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.rpc", providers(), RPC_TRANSPORT, RPC_HANDLER_REGISTRY);
    }

    public static List<RuntimeComponentProvider> providers() {
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_RPC)
                .provide(RPC_TRANSPORT).provide(RPC_HANDLER_REGISTRY).kind(ComponentKind.LOCAL).build(), context -> {
                    InMemoryRpcTransport transport = new InMemoryRpcTransport();
                    return ComponentContribution.builder().bind(RPC_TRANSPORT, transport)
                            .bind(RPC_HANDLER_REGISTRY, transport).build();
                }));
    }
}
