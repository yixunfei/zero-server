package group.zn.zero.net.kcp.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.RedisClient;

/** 隔离存储调用，只验证有界异步执行协议；不建立 Redis 网络连接。 @author zn */
@Timeout(10)
class RedisKcpAsyncAdmissionTest {
    /** 同线程延后执行可接受，但真正的内联执行器仍必须在远程 IO 前拒绝。 */
    @Test void rejectsActualInlineExecutionWithoutRunningOperation() {
        try (var client = RedisClient.create("redis://127.0.0.1:6388")) {
            var store = new RedisKcpSessionStore(client, "async-inline", Runnable::run, 1);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = submit(store, calls::incrementAndGet).toCompletableFuture();
                assertTrue(result.isDone());
                var failure = assertThrows(CompletionException.class,
                        result::join);
                assertTrue(failure.getCause() instanceof IllegalStateException);
            }
            assertEquals(0, calls.get());
        }
    }

    /** 前一步 IO 已结束时，thenCompose 的下一步应能使用容量为一的许可。 */
    @Test void releasesCapacityBeforeCompletingDependentStages() throws Exception {
        try (var client = RedisClient.create("redis://127.0.0.1:6388");
                var executor = Executors.newSingleThreadExecutor()) {
            var store = new RedisKcpSessionStore(client, "async-admission", executor, 1);
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var first = submit(store, () -> {
                entered.countDown();
                await(release);
                return 1;
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var chained = first.thenCompose(value -> submit(store, () -> value + 1));
                release.countDown();
                assertEquals(2, chained.toCompletableFuture().get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    /** 致命业务错误也必须终结返回阶段，并归还许可供后续操作使用。 */
    @Test void supplierErrorCompletesStageAndReleasesCapacity() throws Exception {
        try (var client = RedisClient.create("redis://127.0.0.1:6388");
                var executor = Executors.newSingleThreadExecutor()) {
            var store = new RedisKcpSessionStore(client, "async-error", executor, 1);
            var failure = new AssertionError("injected supplier failure");
            var result = submit(store, () -> { throw failure; }).toCompletableFuture();
            var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertSame(failure, observed.getCause());
            assertTrue(result.isDone());
            assertThrows(CompletionException.class, result::join);
            assertEquals(2, submit(store, () -> 2).toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> submit(final RedisKcpSessionStore store, final Supplier<T> action) {
        try {
            Method method = RedisKcpSessionStore.class.getDeclaredMethod("submit", Supplier.class);
            method.setAccessible(true);
            return (CompletionStage<T>) method.invoke(store, action);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("operation barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
