package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 用受控 future 验证缓存加载容量及取消句柄后的物理写入顺序。 @author zn */
@Timeout(10)
class CacheOperationLifecycleTest {
    /** 完成回调立即加载其他键时，已完成工作的容量必须已经回收。 */
    @Test void localLoadCompletionReleasesCapacityBeforeObserverCallback() {
        var policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, 10, 1, false);
        var cache = new InMemoryCacheService<String, String>(policy);
        var source = new CompletableFuture<Optional<String>>();
        var next = cache.getOrLoad("first", key -> source).thenCompose(value ->
                cache.getOrLoad("second", CacheLoader.sync(key -> Optional.of("next"))));
        source.complete(Optional.of("first"));
        assertEquals(Optional.of("next"), next.toCompletableFuture().join());
    }

    /** 同键共享容量；显式失效不能让尚未结束的旧加载绕过并发上限。 */
    @Test void localLoadsRespectCapacityUntilTheirLifetimeEnds() {
        var policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, 10, 1, false);
        var cache = new InMemoryCacheService<String, String>(policy);
        var source = new CompletableFuture<Optional<String>>();
        var first = cache.getOrLoad("first", key -> source).toCompletableFuture();
        assertSame(first, cache.getOrLoad("first", key -> CompletableFuture.completedFuture(Optional.of("wrong"))));
        cache.invalidate("first");
        ZeroException rejection = assertThrows(ZeroException.class,
                () -> cache.getOrLoad("second", CacheLoader.sync(key -> Optional.of("second"))));
        assertEquals(CacheErrorCode.BACKPRESSURE_REJECTED, rejection.errorCode());
        source.complete(Optional.of("first"));
        first.join();
        assertEquals(Optional.of("second"), cache.getOrLoad("second",
                CacheLoader.sync(key -> Optional.of("second"))).toCompletableFuture().join());
    }

    /** 取消和 Error 均回收加载许可。 */
    @Test void localLoadFailureAndCancellationReleaseCapacity() {
        var policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, 10, 1, false);
        var cache = new InMemoryCacheService<String, String>(policy);
        assertTrue(cache.getOrLoad("cancel", key -> new CompletableFuture<>()).toCompletableFuture().cancel(false));
        var failed = cache.getOrLoad("failure", key -> { throw new AssertionError("capture failed"); });
        assertThrows(java.util.concurrent.CompletionException.class, () -> failed.toCompletableFuture().join());
        assertEquals(Optional.of("value"), cache.getOrLoad("success",
                CacheLoader.sync(key -> Optional.of("value"))).toCompletableFuture().join());
    }

    /** 已发出写入不能因取消观察句柄而在失效之后提交；底层完成阶段模拟不可撤销 IO。 */
    @Test void cancellationDoesNotLetInvalidationOvertakeAcceptedWrite() {
        var store = new ControlledStore();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        var write = cache.putVersioned("key", "old", 1).toCompletableFuture();
        assertTrue(write.cancel(false));
        var invalidation = cache.invalidate("key").toCompletableFuture();
        assertFalse(invalidation.isDone());
        store.write.complete(true);
        invalidation.join();
        assertTrue(cache.get("key").toCompletableFuture().join().isEmpty());
        assertNull(store.current);
    }

    /** 取消尚未执行的同键写入观察句柄，也必须保留与后续失效之间的顺序。 */
    @Test void cancelledQueuedWriteRetainsItsOrderingBarrier() {
        var operations = new CacheKeyOperations<String>();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        operations.mutate("key", token -> first);
        var queued = operations.mutate("key", token -> second).toCompletableFuture();
        queued.cancel(false);
        var third = operations.mutate("key", token -> CompletableFuture.completedFuture("finished"));
        first.complete(null);
        assertFalse(third.toCompletableFuture().isDone());
        second.complete(null);
        assertEquals("finished", third.toCompletableFuture().join());
    }

    /** 后端完成由测试显式推进，无 sleep 或真实中间件。 */
    private static final class ControlledStore implements CacheStore<String, String> {
        /** 不可撤销的后端写入收据。 */
        private final CompletableFuture<Boolean> write = new CompletableFuture<>();
        /** 模拟已提交的后端值。 */
        private CacheStoreEntry<String> current;

        /** @return 当前已提交值，仅测试线程访问。 */
        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(final String key) {
            return CompletableFuture.completedFuture(Optional.ofNullable(current));
        }

        /** @return 可控写入的完成信号。 */
        @Override public CompletionStage<Void> put(final String key, final CacheStoreEntry<String> entry) {
            return putIfVersion(key, entry).thenApply(ignored -> null);
        }

        /** @return 物理写入的可控收据。 */
        @Override public CompletionStage<Boolean> putIfVersion(final String key, final CacheStoreEntry<String> entry) {
            return write.thenApply(saved -> { if (saved) current = entry; return saved; });
        }

        /** 删除已提交值并立即完成。 */
        @Override public CompletionStage<Void> invalidate(final String key) {
            current = null;
            return CompletableFuture.completedFuture(null);
        }

        /** 测试用失效确认。 */
        @Override public CompletionStage<Boolean> invalidateIfVersion(final String key, final long version) {
            return invalidate(key).thenApply(ignored -> true);
        }
    }
}
