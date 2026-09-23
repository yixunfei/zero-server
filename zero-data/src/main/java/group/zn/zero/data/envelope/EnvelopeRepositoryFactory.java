package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.data.repository.RepositoryFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Lazy repositories backed by existing envelope stores. External clients stay with the caller. */
public final class EnvelopeRepositoryFactory implements RepositoryFactory, AutoCloseable {
    private final Function<ZeroDataObjectMetadata, ZeroDataEnvelopeStore> stores;
    private final RepositoryScope scope = new RepositoryScope();
    private final Map<String, Entry> repositories = new HashMap<>();
    private final Map<StorageKey, RepositoryDefinition<?, ?>> storageDefinitions = new HashMap<>();

    public EnvelopeRepositoryFactory(final Function<ZeroDataObjectMetadata, ZeroDataEnvelopeStore> stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    public static EnvelopeRepositoryFactory inMemory() {
        return new EnvelopeRepositoryFactory(metadata -> new InMemoryEnvelopeStore());
    }

    @Override
    public <ID, T extends VersionedEntity<ID>> CrudRepository<ID, T> create(
            final RepositoryDefinition<ID, T> definition) {
        Objects.requireNonNull(definition, "definition");
        return scope.access(() -> createScoped(definition));
    }

    @SuppressWarnings("unchecked")
    private synchronized <ID, T extends VersionedEntity<ID>> CrudRepository<ID, T> createScoped(
            final RepositoryDefinition<ID, T> definition) {
        Entry previous = repositories.get(definition.name());
        if (previous != null) {
            if (!previous.definition().equals(definition)) {
                throw new IllegalArgumentException("repository name is already bound to another definition");
            }
            return (CrudRepository<ID, T>) previous.repository();
        }
        var key = new StorageKey(definition.metadata().namespace(), definition.metadata().collection());
        if (storageDefinitions.containsKey(key)) {
            throw new IllegalArgumentException("storage namespace/collection is already bound to another repository");
        }
        var codec = new ZeroDataEntityCodec<ID, T>(definition.metadata(), definition.codec(), definition.codecVersion());
        ZeroDataEnvelopeStore store;
        try {
            store = Objects.requireNonNull(stores.apply(definition.metadata()), "envelope store");
        } catch (RuntimeException failure) {
            throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE, "repository storage creation failed", null);
        }
        CrudRepository<ID, T> repository = new ZeroDataEnvelopeCrudRepository<>(codec, new ScopedEnvelopeStore(scope, store));
        repositories.put(definition.name(), new Entry(definition, repository));
        storageDefinitions.put(key, definition);
        return repository;
    }

    @Override
    public void close() {
        scope.close();
        synchronized (this) {
            repositories.clear();
            storageDefinitions.clear();
        }
    }

    private record Entry(RepositoryDefinition<?, ?> definition, CrudRepository<?, ?> repository) { }
    private record StorageKey(String namespace, String collection) { }
}
