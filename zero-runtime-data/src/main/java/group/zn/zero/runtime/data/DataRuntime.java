package group.zn.zero.runtime.data;

import group.zn.zero.data.DataService;
import group.zn.zero.data.persistence.DefaultPersistenceManager;
import group.zn.zero.data.persistence.PersistenceManager;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.repository.RepositoryCatalog;
import group.zn.zero.data.repository.RepositorySource;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.api.RuntimeLifecycleCapabilities;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;
import java.util.Map;

/** Explicit data bindings and lazy local providers. */
public final class DataRuntime {
    public static final ComponentKey<PersistenceManager> PERSISTENCE_MANAGER = ComponentKey.single(
            StandardRuntimeCapabilityModel.PERSISTENCE_MANAGER, PersistenceManager.class);
    public static final ComponentSetKey<DataService> DATA_SERVICES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.DATA_SERVICES, DataService.class);
    public static final ComponentSetKey<RepositorySource> REPOSITORY_SOURCES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.REPOSITORY_SOURCES, RepositorySource.class);
    public static final ComponentKey<RepositoryCatalog> REPOSITORIES = ComponentKey.single(
            StandardRuntimeCapabilityModel.REPOSITORIES, RepositoryCatalog.class);

    private DataRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.data", providers(), PERSISTENCE_MANAGER, REPOSITORY_SOURCES);
    }

    /** 注入快照所属执行域；传入 Actor 适配器即可在绑定 lane 捕获，资源由调用方管理。 */
    public static RuntimeModule module(final group.zn.zero.data.persistence.PersistenceBindingExecutor executor) {
        return RuntimeModule.of("zero.data", providers(executor), PERSISTENCE_MANAGER, REPOSITORY_SOURCES);
    }

    /** Binds application roles explicitly, for example game -> mongo or game -> local. */
    public static RuntimeModule repositories(final Map<String, String> roleBindings) {
        Map<String, String> checked = Map.copyOf(roleBindings);
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("zero.data.repository-catalog"))
                .provide(REPOSITORIES).require(REPOSITORY_SOURCES).kind(ComponentKind.FOUNDATION).build(), context ->
                ComponentContribution.builder().bind(REPOSITORIES,
                        new RepositoryCatalog(context.requireAll(REPOSITORY_SOURCES), checked)).build());
        return RuntimeModule.of("zero.data.repository-catalog", List.of(provider), REPOSITORIES);
    }

    public static List<RuntimeComponentProvider> providers() {
        return providers(new UnboundSnapshotExecutor());
    }

    /** 创建使用指定快照执行器的 provider；不可为空，不创建线程。 */
    public static List<RuntimeComponentProvider> providers(
            final group.zn.zero.data.persistence.PersistenceBindingExecutor executor) {
        java.util.Objects.requireNonNull(executor, "executor");
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE)
                .provide(PERSISTENCE_MANAGER).optional(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES)
                .kind(ComponentKind.LOCAL).build(), context -> {
                    context.requireAll(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES);
                    DefaultPersistenceManager persistence = new DefaultPersistenceManager(executor);
                    return ComponentContribution.builder().bind(PERSISTENCE_MANAGER, persistence)
                            .lifecycle(persistence).build();
                }), RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_REPOSITORIES)
                .provide(REPOSITORY_SOURCES).require(RuntimeBasics.EXECUTORS).kind(ComponentKind.LOCAL).build(), context -> {
                    var factory = context.resources().register(EnvelopeRepositoryFactory.inMemory(
                            context.require(RuntimeBasics.EXECUTORS).remoteIoExecutor()));
                    return ComponentContribution.builder()
                            .contribute(REPOSITORY_SOURCES, new RepositorySource("local", factory)).build();
                }));
    }

    /** 默认仅接受明确未绑定的快照；有绑定实体必须显式装配其执行域。 */
    private static final class UnboundSnapshotExecutor implements group.zn.zero.data.persistence.PersistenceBindingExecutor {
        /** 纯内存未绑定快照执行器。 */
        private final group.zn.zero.data.persistence.DirectPersistenceBindingExecutor direct =
                new group.zn.zero.data.persistence.DirectPersistenceBindingExecutor();

        @Override public <T> java.util.concurrent.CompletionStage<T> capture(
                final group.zn.zero.data.persistence.DataThreadBinding binding,
                final java.util.function.Supplier<T> snapshotSupplier) {
            if (!binding.isUnbound()) return java.util.concurrent.CompletableFuture.failedFuture(
                    group.zn.zero.core.error.ZeroException.of(group.zn.zero.data.error.DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                            "bound snapshots require an explicit persistence binding executor", null));
            return direct.capture(binding, snapshotSupplier);
        }
    }
}
