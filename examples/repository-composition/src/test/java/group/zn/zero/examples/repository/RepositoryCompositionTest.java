package group.zn.zero.examples.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    /** 外部公开组合根必须构建受管非内联 IO；不启动或连接数据库。 */
    @Test
    void externalCompositionRootsUseManagedRemoteIo() throws Exception {
        var properties = java.util.Map.of(
                "zero.mongo.uri", "mongodb://127.0.0.1:1", "zero.mongo.database", "assembly_test",
                "zero.redis.uri", "redis://127.0.0.1:1",
                "zero.postgresql.url", "jdbc:postgresql://127.0.0.1:1/assembly_test",
                "zero.postgresql.username", "assembly_test", "zero.postgresql.password", "assembly-test-only",
                "zero.postgresql.table", "assembly_test");
        var previous = new java.util.HashMap<String, String>();
        properties.forEach((key, value) -> previous.put(key, System.getProperty(key)));
        try {
            properties.forEach(System::setProperty);
            for (String backend : java.util.List.of("mongo", "redis", "postgresql")) {
                var runtime = RepositoryCompositionApplication.runtime(backend);
                var executors = runtime.require(RuntimeBasics.EXECUTORS);
                try {
                    assertFalse(executors.remoteIoMayInline());
                    Thread caller = Thread.currentThread();
                    assertFalse(java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> Thread.currentThread() == caller, executors.remoteIoExecutor())
                            .get(3, java.util.concurrent.TimeUnit.SECONDS));
                } finally { runtime.close(); }
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> executors.remoteIoExecutor().execute(() -> { }));
            }
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
    }

    @Test
    void sameBusinessServiceUsesEachExistingEnvelopeRepresentation() {
        List<ZeroDataEnvelopeStore> stores = List.of(new InMemoryEnvelopeStore(), new MongoDataEnvelopeStore(),
                new PostgresqlDataEnvelopeStore(),
                new RedisDataEnvelopeStore("composition_example", "balances"));
        for (ZeroDataEnvelopeStore store : stores) {
            try (var factory = new EnvelopeRepositoryFactory(metadata -> store, Runnable::run)) {
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
