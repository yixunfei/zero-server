package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 写入观察期限不能撤销排队工作、释放物理容量或破坏同键顺序。 @author zn */
class CacheWriteObservationTest {
    @Test void timeoutKeepsAcceptedQueueAndCapacityUntilActualCompletion() throws Exception {
        var operations = new CacheKeyOperations<String>(Duration.ofMillis(100));
        var physical = new CompletableFuture<Integer>();
        var observed = operations.mutate("key", token -> physical).toCompletableFuture();
        var calls = new AtomicInteger();
        CompletableFuture<Integer> tail = null;
        for (int i = 1; i < 1024; i++) {
            tail = operations.mutate("key", token -> CompletableFuture.completedFuture(calls.incrementAndGet()))
                    .toCompletableFuture();
        }
        assertTimeout(observed);
        assertTimeout(tail);
        assertFalse(physical.isDone());
        assertEquals(0, calls.get());
        assertEquals(CacheErrorCode.BACKPRESSURE_REJECTED, assertThrows(ZeroException.class,
                () -> operations.mutate("key", token -> CompletableFuture.completedFuture(0))).errorCode());
        assertEquals(7, operations.mutate("independent", token -> CompletableFuture.completedFuture(7))
                .toCompletableFuture().join());
        physical.complete(1);
        assertEquals(1023, calls.get());
        assertEquals(8, operations.mutate("key", token -> CompletableFuture.completedFuture(8)).toCompletableFuture().join());
    }

    @Test void backfillTimeoutKeepsPhysicalBarrier() throws Exception {
        var operations = new CacheKeyOperations<String>(Duration.ofMillis(30));
        var token = operations.read("key");
        var physical = new CompletableFuture<Integer>();
        var observed = operations.backfill(token, () -> physical, () -> CompletableFuture.completedFuture(-1))
                .toCompletableFuture();
        var calls = new AtomicInteger();
        var next = operations.mutate("key", ignored -> CompletableFuture.completedFuture(calls.incrementAndGet()));
        assertTimeout(observed);
        assertEquals(0, calls.get());
        assertFalse(physical.isCancelled());
        physical.complete(1);
        assertEquals(1, calls.get());
        operations.release(token);
        assertTrue(next.toCompletableFuture().isDone());
    }

    @Test void externalRecoveryTicketCannotHideLaterOrBackendFailures() {
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults());
        long old = cache.markWriteBackFailure();
        long current = cache.markWriteBackFailure();
        assertFalse(cache.confirmWriteBackRecovery(old));
        cache.put("local", "success").toCompletableFuture().join();
        assertTrue(cache.degraded());
        cache.addBacklog(1);
        assertTrue(cache.confirmWriteBackRecovery(current));
        assertTrue(cache.degraded());
        cache.clearBacklog();
        assertFalse(cache.degraded());
        cache.markInternalWriteBackFailure();
        long external = cache.markWriteBackFailure();
        assertTrue(cache.confirmWriteBackRecovery(external));
        assertTrue(cache.degraded());
        assertEquals(4, cache.healthSnapshot().writeBackFailureCount());
    }

    private static void assertTimeout(final CompletableFuture<?> observed) throws Exception {
        var failure = assertThrows(ExecutionException.class, () -> observed.get(2, TimeUnit.SECONDS));
        assertEquals(CacheErrorCode.WRITE_TIMEOUT, ((ZeroException) failure.getCause()).errorCode());
    }
}
