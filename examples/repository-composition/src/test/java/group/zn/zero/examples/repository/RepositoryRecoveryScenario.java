package group.zn.zero.examples.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.repository.CrudRepository;
import java.time.Duration;
import java.util.concurrent.CompletionException;

final class RepositoryRecoveryScenario {
    private RepositoryRecoveryScenario() { }

    static void verify(final String backend) throws Exception {
        try (var clients = RepositoryClients.open(backend, 1)) {
            var repository = clients.repository(0);
            var service = new BalanceService(repository);
            service.credit("recovery", 10).toCompletableFuture().join();
            long failureMillis;
            long recoveryMillis;
            try (var database = new IsolatedDatabase(backend)) {
                database.stop();
                long start = System.nanoTime();
                var failure = assertThrows(CompletionException.class,
                        () -> repository.findById("recovery").toCompletableFuture().join());
                failureMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
                assertEquals(DataErrorCode.READ_FAILED, ((ZeroException) failure.getCause()).errorCode());
                assertTrue(failureMillis < 15_000, "outage read exceeded its test budget");
                start = System.nanoTime();
                database.start();
                awaitStored(repository, new Balance("recovery", 1, 10));
                recoveryMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            }
            assertEquals(new Balance("recovery", 2, 15), service.credit("recovery", 5).toCompletableFuture().join());
            repository.deleteById("recovery").toCompletableFuture().join();
            assertTrue(repository.findById("recovery").toCompletableFuture().join().isEmpty());
            System.out.println("repository-recovery=ok|backend=" + backend + "|sameClient=true|sameRepository=true"
                    + "|persisted=true|outageReadMs=" + failureMillis + "|restartAndRecoveryMs=" + recoveryMillis);
        }
    }

    private static void awaitStored(final CrudRepository<String, Balance> repository, final Balance expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                assertEquals(expected, repository.findById(expected.id()).toCompletableFuture().join().orElseThrow());
                return;
            } catch (CompletionException failure) {
                if (!(failure.getCause() instanceof ZeroException zero) || zero.errorCode() != DataErrorCode.READ_FAILED
                        || System.nanoTime() >= deadline) { throw failure; }
                Thread.sleep(200);
            }
        }
    }
}
