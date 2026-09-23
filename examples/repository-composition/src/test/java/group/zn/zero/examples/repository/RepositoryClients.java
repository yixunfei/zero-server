package group.zn.zero.examples.repository;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.runtime.api.GameRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** External workers own independent runtimes; local workers share the runtime-owned memory store. */
final class RepositoryClients implements AutoCloseable {
    private final List<GameRuntime> runtimes = new ArrayList<>();
    private final List<CrudRepository<String, Balance>> repositories = new ArrayList<>();

    private RepositoryClients() { }

    static RepositoryClients open(final String backend, final int workers) {
        var clients = new RepositoryClients();
        var definition = RepositoryTestRuntime.definition("workload", "resilience_" + UUID.randomUUID().toString().replace("-", ""));
        try {
            for (int worker = 0; worker < workers; worker++) {
                if (backend.equals("local") && worker > 0) {
                    clients.repositories.add(clients.repositories.getFirst());
                } else {
                    var runtime = RepositoryTestRuntime.runtime(backend);
                    clients.runtimes.add(runtime);
                    runtime.start();
                    clients.repositories.add(RepositoryTestRuntime.repository(runtime, definition));
                }
            }
            return clients;
        } catch (RuntimeException | Error failure) {
            try { clients.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    CrudRepository<String, Balance> repository(final int worker) {
        return repositories.get(worker);
    }

    static boolean isConflict(final CompletionException failure) {
        return failure.getCause() instanceof ZeroException zero && zero.errorCode() == DataErrorCode.VERSION_CONFLICT;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var runtime : runtimes.reversed()) {
            try { runtime.close(); } catch (RuntimeException cleanup) {
                if (failure == null) { failure = cleanup; } else { failure.addSuppressed(cleanup); }
            }
        }
        if (failure != null) { throw failure; }
    }
}
