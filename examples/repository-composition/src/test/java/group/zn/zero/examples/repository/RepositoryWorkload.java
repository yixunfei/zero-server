package group.zn.zero.examples.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded consistency workload, not a capacity benchmark or a production retry policy. */
final class RepositoryWorkload {
    private static final int WORKERS = 4;
    private final String backend;
    private final LongAdder successes = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong();
    private final AtomicLong nextProgress = new AtomicLong();

    private RepositoryWorkload(final String backend) {
        this.backend = backend;
    }

    static void concurrent(final String backend) throws Exception {
        try (var clients = RepositoryClients.open(backend, WORKERS)) {
            race(clients, 0);
            race(clients, 1);
            var workload = new RepositoryWorkload(backend);
            long start = System.nanoTime();
            var tasks = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < WORKERS; worker++) {
                var service = new BalanceService(clients.repository(worker));
                tasks.add(() -> {
                    for (int operation = 0; operation < 100; operation++) { workload.credit(service); }
                    return null;
                });
            }
            runAll(tasks, Duration.ofSeconds(120));
            workload.assertBalanceAndDelete(clients, 2);
            assertEquals(400, workload.successes.sum());
            workload.report("concurrency", start);
        }
    }

    static void soak(final String backend, final int seconds) throws Exception {
        try (var clients = RepositoryClients.open(backend, WORKERS)) {
            var workload = new RepositoryWorkload(backend);
            long start = System.nanoTime();
            long deadline = start + Duration.ofSeconds(seconds).toNanos();
            workload.nextProgress.set(start + Duration.ofSeconds(30).toNanos());
            var tasks = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < WORKERS; worker++) {
                var service = new BalanceService(clients.repository(worker));
                tasks.add(() -> {
                    long completed = 0;
                    while (System.nanoTime() < deadline) {
                        workload.credit(service);
                        completed++;
                        workload.progress(start);
                        Thread.sleep(10);
                    }
                    assertTrue(completed > 0, "every worker must complete business operations");
                    return null;
                });
            }
            runAll(tasks, Duration.ofSeconds(seconds + 120L));
            assertTrue(System.nanoTime() >= deadline, "workload ended before the requested duration");
            workload.assertBalanceAndDelete(clients, 0);
            workload.report("soak", start);
        }
    }

    private static void race(final RepositoryClients clients, final long version) throws Exception {
        var ready = new CountDownLatch(WORKERS);
        var release = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int worker = 0; worker < WORKERS; worker++) {
            var repository = clients.repository(worker);
            tasks.add(() -> {
                var previous = repository.findById("balance").toCompletableFuture().join()
                        .orElse(new Balance("balance", 0, 0));
                assertEquals(version, previous.version());
                ready.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS), "race did not start");
                try {
                    repository.save(new Balance("balance", previous.version(), previous.amount() + 1))
                            .toCompletableFuture().join();
                    return true;
                } catch (CompletionException failure) {
                    if (!RepositoryClients.isConflict(failure)) { throw failure; }
                    return false;
                }
            });
        }
        tasks.add(() -> {
            try { assertTrue(ready.await(15, TimeUnit.SECONDS), "workers did not read the same version"); }
            finally { release.countDown(); }
            return false;
        });
        assertEquals(1, runAll(tasks, Duration.ofSeconds(45)).stream().filter(Boolean::booleanValue).count());
        assertEquals(new Balance("balance", version + 1, version + 1),
                clients.repository(0).findById("balance").toCompletableFuture().join().orElseThrow());
    }

    private void credit(final BalanceService service) throws InterruptedException {
        long start = System.nanoTime();
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("workload cancelled"); }
            try {
                service.credit("balance", 1).toCompletableFuture().join();
                successes.increment();
                long elapsed = System.nanoTime() - start;
                latencyNanos.add(elapsed);
                maxLatencyNanos.accumulateAndGet(elapsed, Math::max);
                return;
            } catch (CompletionException failure) {
                // Only a rejected CAS is known not to have applied the credit. Transport failures are fatal.
                if (!RepositoryClients.isConflict(failure)) {
                    report("failed", start);
                    throw failure;
                }
                conflicts.increment();
                Thread.sleep(1);
            }
        }
        throw new AssertionError("CAS retry limit exceeded for " + backend);
    }

    private void assertBalanceAndDelete(final RepositoryClients clients, final long initial) {
        long expected = initial + successes.sum();
        for (int worker = 0; worker < WORKERS; worker++) {
            var repository = clients.repository(worker);
            assertEquals(new Balance("balance", expected, expected),
                    repository.findById("balance").toCompletableFuture().join().orElseThrow());
            assertEquals(1L, repository.count().toCompletableFuture().join());
        }
        clients.repository(0).deleteById("balance").toCompletableFuture().join();
        assertEquals(0L, clients.repository(0).count().toCompletableFuture().join());
    }

    private void progress(final long start) {
        long now = System.nanoTime();
        long next = nextProgress.get();
        if (now >= next && nextProgress.compareAndSet(next, now + Duration.ofSeconds(30).toNanos())) {
            report("progress", start);
        }
    }

    private void report(final String phase, final long start) {
        long count = successes.sum();
        System.out.println("repository-" + phase + "=" + (phase.equals("failed") ? "failed" : "ok")
                + "|backend=" + backend + "|workers=" + WORKERS
                + "|clientScope=" + (backend.equals("local") ? "shared-runtime-memory" : "independent-runtimes")
                + "|elapsedMs=" + Duration.ofNanos(System.nanoTime() - start).toMillis()
                + "|credits=" + count + "|conflicts=" + conflicts.sum()
                + "|meanCreditMicros=" + (count == 0 ? 0 : latencyNanos.sum() / count / 1_000)
                + "|maxCreditMicros=" + maxLatencyNanos.get() / 1_000);
    }

    static <T> List<T> runAll(final List<Callable<T>> tasks, final Duration timeout) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size(), Thread.ofPlatform().daemon().factory());
        var completed = new ExecutorCompletionService<T>(executor);
        var futures = new ArrayList<Future<T>>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            for (var task : tasks) { futures.add(completed.submit(task)); }
            var results = new ArrayList<T>();
            for (int remaining = tasks.size(); remaining > 0; remaining--) {
                var future = completed.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (future == null) { throw new TimeoutException("repository workload timed out"); }
                results.add(future.get());
            }
            return results;
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("repository workers did not terminate");
            }
        }
    }
}
