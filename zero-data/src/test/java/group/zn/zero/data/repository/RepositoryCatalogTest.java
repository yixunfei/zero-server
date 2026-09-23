package group.zn.zero.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.envelope.InMemoryEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RepositoryCatalogTest {
    private static final RepositoryDefinition<String, Balance> BALANCES = RepositoryDefinition.of(
            "balances", String.class, Balance.class, new BalanceCodec(), 1);

    @Test
    void explicitRolesShareTypedRepositoriesAndPreserveVersionSemantics() {
        AtomicInteger stores = new AtomicInteger();
        try (var factory = new EnvelopeRepositoryFactory(metadata -> {
            stores.incrementAndGet();
            return new InMemoryEnvelopeStore();
        })) {
            var catalog = new RepositoryCatalog(List.of(new RepositorySource("chosen", factory)),
                    Map.of("game", "chosen", "audit", "chosen"));
            assertEquals(0, stores.get());
            CrudRepository<String, Balance> repository = catalog.create(new RepositoryRequest<>("game", BALANCES));
            assertSame(repository, catalog.create(new RepositoryRequest<>("audit", BALANCES)));
            repository.save(new Balance("player", 0, 10)).toCompletableFuture().join();
            Balance saved = repository.findById("player").toCompletableFuture().join().orElseThrow();
            assertEquals(new Balance("player", 1, 10), saved);
            var conflict = assertThrows(CompletionException.class,
                    () -> repository.save(new Balance("player", 0, 11)).toCompletableFuture().join());
            assertEquals(DataErrorCode.VERSION_CONFLICT, ((ZeroException) conflict.getCause()).errorCode());
            assertEquals(1, stores.get());
        }
    }

    @Test
    void rejectsMissingRolesDuplicateSourcesAndConflictingDefinitions() {
        try (var factory = EnvelopeRepositoryFactory.inMemory()) {
            var source = new RepositorySource("local", factory);
            assertThrows(IllegalArgumentException.class,
                    () -> new RepositoryCatalog(List.of(source, source), Map.of()));
            assertThrows(ZeroException.class,
                    () -> new RepositoryCatalog(List.of(source), Map.of("game", "absent")));
            var catalog = new RepositoryCatalog(List.of(source), Map.of());
            assertThrows(ZeroException.class, () -> catalog.create(new RepositoryRequest<>("game", BALANCES)));
            factory.create(BALANCES);
            assertThrows(IllegalArgumentException.class, () -> factory.create(RepositoryDefinition.of(
                    "balances", String.class, Balance.class, new BalanceCodec(), 2)));
            assertThrows(IllegalArgumentException.class, () -> factory.create(RepositoryDefinition.of(
                    "another-name", String.class, Balance.class, new BalanceCodec(), 1)));
        }
    }

    @Test
    void closedFactoriesInvalidateRetainedRepositoriesAndDoNotLeakStorageFailures() {
        var factory = EnvelopeRepositoryFactory.inMemory();
        var repository = factory.create(BALANCES);
        factory.close();
        assertThrows(ZeroException.class, () -> factory.create(BALANCES));
        var failure = assertThrows(CompletionException.class,
                () -> repository.count().toCompletableFuture().join());
        assertEquals(DataErrorCode.BACKEND_UNAVAILABLE, ((ZeroException) failure.getCause()).errorCode());
        try (var broken = new EnvelopeRepositoryFactory(metadata -> {
            throw new IllegalStateException("private-connection-string");
        })) {
            var error = assertThrows(ZeroException.class, () -> broken.create(BALANCES));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("private-connection-string"));
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsMismatchedIdAndCodecTypesBeforeStorageCreation() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryDefinition(
                "invalid", Long.class, Balance.class, BALANCES.metadata(), BALANCES.codec(), 1));
        var codec = new BalanceCodec() {
            @Override public Class<Balance> messageType() { return (Class) String.class; }
        };
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryDefinition.of("invalid", String.class, Balance.class, codec, 1));
    }

    @ZeroDataObject(namespace = "test", collection = "balances", schemaVersion = 1)
    private record Balance(@ZeroDataId String id, @ZeroDataVersion long version,
                           @ZeroDataField(order = 1) int amount) implements VersionedEntity<String> {
        @Override public Balance withVersion(final long next) { return new Balance(id, next, amount); }
    }

    private static class BalanceCodec implements ZeroPayloadCodec<Balance> {
        @Override public String name() { return "balance"; }
        @Override public Class<Balance> messageType() { return Balance.class; }
        @Override public void write(final ZeroWriter writer, final Balance value) {
            writer.writeString(value.id()); writer.writeLong(value.version()); writer.writeInt(value.amount());
        }
        @Override public Balance read(final ZeroReader reader) {
            return new Balance(reader.readString(), reader.readLong(), reader.readInt());
        }
    }
}
