package group.zn.zero.starter.production;

import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.discovery.nacos.ServiceDiscoveryRpcServiceResolver;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.Objects;

/** 把中立 ServiceDiscovery 显式桥接为 RpcServiceResolver 的进程内基础 provider。 */
final class ProductionNacosRpcResolverProvider implements RuntimeComponentProvider {

    /** 标准能力模型中的 provider ID。 */
    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER;

    /** Runtime provider 描述。 */
    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(ProductionRuntimeCapabilities.RPC_SERVICE_RESOLVER)
            .require(ProductionRuntimeCapabilities.SERVICE_DISCOVERY)
            .kind(ComponentKind.FOUNDATION)
            .build();

    /** 与 Nacos discovery 共用的安全诊断状态。 */
    private final ProductionAdapterDiagnostic diagnostic;

    ProductionNacosRpcResolverProvider(final ProductionAdapterDiagnostic diagnostic) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentCreationContext checked = Objects.requireNonNull(context, "context");
        try {
            ServiceDiscovery discovery = checked.require(ProductionRuntimeCapabilities.SERVICE_DISCOVERY);
            RpcServiceResolver created = new ServiceDiscoveryRpcServiceResolver(discovery);
            return ComponentContribution.builder()
                    .bind(ProductionRuntimeCapabilities.RPC_SERVICE_RESOLVER, created)
                    .build();
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    diagnostic.adapterName(),
                    ProductionAdapterFailurePhase.CLIENT_CREATION,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                    failure);
            diagnostic.fail(safeFailure);
            throw safeFailure;
        }
    }

}
