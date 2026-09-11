package group.zn.zero.examples.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.envelope.InMemoryEnvelopeStore;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.mongo.MongoDataEnvelopeStore;
import group.zn.zero.data.postgresql.PostgresqlDataEnvelopeStore;
import group.zn.zero.data.redis.RedisDataEnvelopeStore;
import group.zn.zero.data.repository.RepositoryRequest;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.data.repository.RepositoryFactory;
import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class RepositoryCompositionTest {
    @Test
    void sameBusinessServiceUsesEachExistingEnvelopeRepresentation() {
        List<ZeroDataEnvelopeStore> stores = List.of(new InMemoryEnvelopeStore(), new MongoDataEnvelopeStore(),
                new PostgresqlDataEnvelopeStore(),
                new RedisDataEnvelopeStore("composition_example", "balances"));
        for (ZeroDataEnvelopeStore store : stores) {
            try (var factory = new EnvelopeRepositoryFactory(metadata -> store)) {
                var repository = factory.create(RepositoryCompositionApplication.BALANCES);
                var service = new BalanceService(repository);
                assertEquals(1, service.credit("player", 10).toCompletableFuture().join().version());
                assertEquals(new Balance("player", 2, 15), service.credit("player", 5).toCompletableFuture().join());
                repository.deleteById("player").toCompletableFuture().join();
                assertEquals(0, repository.count().toCompletableFuture().join());
            }
        }
    }

    @Test
    void laterProviderFailureClosesPreviouslyCreatedRepositoryFactories() {
        var captured = new AtomicReference<RepositoryFactory>();
        var key = ComponentKey.single("example.failure", Runnable.class);
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("example.failure"))
                .provide(key).require(DataRuntime.REPOSITORY_SOURCES).build(), context -> {
                    captured.set(context.requireAll(DataRuntime.REPOSITORY_SOURCES).getFirst().factory());
                    throw new IllegalStateException("example build failure");
                });
        var composition = RuntimeBasics.builder().install(DataRuntime.module()).register(provider)
                .override(key, provider.descriptor().id()).require(key);
        assertThrows(RuntimeAssemblyException.class, composition::build);
        assertNotNull(captured.get());
        assertThrows(ZeroException.class, () -> captured.get().create(RepositoryCompositionApplication.BALANCES));
    }

    @Test
    void localRuntimeExposesTheCatalogAndClosesRetainedRepositories() {
        var runtime = RepositoryCompositionApplication.runtime("local");
        runtime.start();
        var repository = runtime.require(DataRuntime.REPOSITORIES)
                .create(new RepositoryRequest<>("game", RepositoryCompositionApplication.BALANCES));
        assertEquals(10, new BalanceService(repository).credit("player", 10).toCompletableFuture().join().amount());
        runtime.close();
        assertThrows(CompletionException.class, () -> repository.count().toCompletableFuture().join());
    }
}
