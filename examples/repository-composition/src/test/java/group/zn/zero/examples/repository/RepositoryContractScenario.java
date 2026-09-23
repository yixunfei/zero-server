package group.zn.zero.examples.repository;

import static group.zn.zero.examples.repository.RepositoryTestRuntime.definition;
import static group.zn.zero.examples.repository.RepositoryTestRuntime.repository;
import static group.zn.zero.examples.repository.RepositoryTestRuntime.runtime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.RepositoryDefinition;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** The same business contract runs against local storage and explicitly selected real drivers. */
final class RepositoryContractScenario {
    private RepositoryContractScenario() { }

    static void verify(final String backend) {
        String run = UUID.randomUUID().toString().replace("-", "");
        List<RepositoryDefinition<String, Balance>> definitions = List.of(
                definition("primary", "contract-" + run), definition("secondary", "contract_" + run));
        boolean persistent = !backend.equals("local");
        try {
            exercise(backend, definitions, persistent);
            if (persistent) {
                try (var runtime = runtime(backend)) {
                    runtime.start();
                    assertStored(repository(runtime, definitions.getFirst()), 15);
                    assertStored(repository(runtime, definitions.getLast()), 25);
                }
            }
        } finally {
            if (persistent) {
                try (var runtime = runtime(backend)) {
                    runtime.start();
                    definitions.forEach(definition -> cleanup(repository(runtime, definition)));
                }
            }
        }
        System.out.println("repository-contract=ok|backend=" + backend + "|crud=true|cas=true|namespaceIsolation=true"
                + "|reopen=" + persistent + "|closedAccessRejected=true|recordsCleaned=true|run=" + run);
    }

    private static void exercise(final String backend, final List<RepositoryDefinition<String, Balance>> definitions,
                                 final boolean persistent) {
        CrudRepository<String, Balance> retained;
        try (var runtime = runtime(backend)) {
            runtime.start();
            retained = repository(runtime, definitions.getFirst());
            var secondary = repository(runtime, definitions.getLast());
            try {
                creditAndCheck(retained, 10);
                creditAndCheck(secondary, 20);
                assertStored(retained, 15);
                assertStored(secondary, 25);
            } finally {
                if (!persistent) {
                    cleanup(retained);
                    cleanup(secondary);
                }
            }
        }
        assertThrows(CompletionException.class, () -> retained.findById("same-id").toCompletableFuture().join());
    }

    private static void creditAndCheck(final CrudRepository<String, Balance> repository, final int initial) {
        var service = new BalanceService(repository);
        Balance first = service.credit("same-id", initial).toCompletableFuture().join();
        assertEquals(1L, first.version());
        assertEquals(2L, service.credit("same-id", 5).toCompletableFuture().join().version());
        var failure = assertThrows(CompletionException.class,
                () -> repository.save(first).toCompletableFuture().join());
        assertEquals(DataErrorCode.VERSION_CONFLICT, ((ZeroException) failure.getCause()).errorCode());
    }

    private static void assertStored(final CrudRepository<String, Balance> repository, final int amount) {
        assertEquals(new Balance("same-id", 2L, amount),
                repository.findById("same-id").toCompletableFuture().join().orElseThrow());
        assertEquals(1L, repository.count().toCompletableFuture().join());
    }

    private static void cleanup(final CrudRepository<String, Balance> repository) {
        repository.deleteById("same-id").toCompletableFuture().join();
        assertFalse(repository.existsById("same-id").toCompletableFuture().join());
    }

}
