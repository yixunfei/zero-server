package group.zn.zero.runtime.data;

import group.zn.zero.data.DataService;
import group.zn.zero.data.persistence.DefaultPersistenceManager;
import group.zn.zero.data.persistence.PersistenceManager;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.repository.RepositoryCatalog;
import group.zn.zero.data.repository.RepositorySource;
import group.zn.zero.runtime.api.ComponentId;
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
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE)
                .provide(PERSISTENCE_MANAGER).optional(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES)
                .kind(ComponentKind.LOCAL).build(), context -> {
                    context.requireAll(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES);
                    DefaultPersistenceManager persistence = new DefaultPersistenceManager();
                    return ComponentContribution.builder().bind(PERSISTENCE_MANAGER, persistence)
                            .lifecycle(persistence).build();
                }), RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_REPOSITORIES)
                .provide(REPOSITORY_SOURCES).kind(ComponentKind.LOCAL).build(), context -> {
                    var factory = context.resources().register(EnvelopeRepositoryFactory.inMemory());
                    return ComponentContribution.builder()
                            .contribute(REPOSITORY_SOURCES, new RepositorySource("local", factory)).build();
                }));
    }
}
