package group.zn.zero.starter.production;

import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.Objects;

/** Redis data Adapter 的显式 external runtime provider。 */
final class ProductionRedisDataProvider implements RuntimeComponentProvider {

    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA;

    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(ProductionRuntimeCapabilities.DATA_SERVICES)
            .require(ProductionRedisRuntimeCapabilities.RESOURCE)
            .kind(ComponentKind.EXTERNAL)
            .health(HealthPhase.STARTUP)
            .build();
    private final ProductionAdapterDiagnostic diagnostic;
    private final ProductionStartupBudget startupBudget;

    ProductionRedisDataProvider(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentCreationContext checked = Objects.requireNonNull(context, "context");
        try {
            ProductionRedisResource resource = checked.require(ProductionRedisRuntimeCapabilities.RESOURCE);
            resource.acquire(checked.resources());
            RedisDataAdapter created = new RedisDataAdapter();
            diagnostic.mark(ZeroProductionAdapterState.CREATED);
            return ComponentContribution.builder()
                    .contribute(ProductionRuntimeCapabilities.DATA_SERVICES, created)
                    .lifecycle(new ProductionAdapterLifecycle(diagnostic, startupBudget, created))
                    .healthProbe(HealthPhase.STARTUP, new ProductionStartupHealthProbe(
                            diagnostic,
                            timeout -> ProductionAdapterHealthProbes.redisData(resource.settings(), timeout),
                            startupBudget))
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
