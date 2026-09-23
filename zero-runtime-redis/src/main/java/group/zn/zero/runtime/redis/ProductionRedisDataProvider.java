package group.zn.zero.runtime.redis;

import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.data.repository.RepositorySource;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterLifecycle;
import group.zn.zero.runtime.production.ProductionStartupBudget;
import group.zn.zero.runtime.production.ProductionStartupHealthProbe;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
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
            .provide(DataRuntime.DATA_SERVICES)
            .provide(DataRuntime.REPOSITORY_SOURCES)
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
            var repositories = checked.resources().register(created.repositoryFactory(resource.client()));
            return ComponentContribution.builder()
                    .contribute(DataRuntime.DATA_SERVICES, created)
                    .contribute(DataRuntime.REPOSITORY_SOURCES, new RepositorySource("redis", repositories))
                    .lifecycle(new ProductionAdapterLifecycle(diagnostic, startupBudget, created))
                    .healthProbe(HealthPhase.STARTUP, new ProductionStartupHealthProbe(
                            diagnostic,
                            timeout -> RedisStartupProbes.data(resource.settings(), timeout),
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
