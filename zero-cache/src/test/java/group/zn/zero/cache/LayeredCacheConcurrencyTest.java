package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 分层缓存条件失效和超时加载边界回归。 @author zn */
class LayeredCacheConcurrencyTest {
    /** L2 拒绝条件失效时，已有 L1 条目继续可读。 */
    @Test
    void rejectedInvalidationPreservesLocalEntry() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        cache.putVersioned("key", "current", 1).toCompletableFuture().join();
        var invalidation = cache.invalidateIfVersion("key", 1).toCompletableFuture();
        store.invalidation.complete(false);
        assertFalse(invalidation.join());
        assertEquals(Optional.of("current"), cache.get("key").toCompletableFuture().join());
        assertEquals(0, store.reads);
    }

    /** L2 确认期间同 key 的新写入不能被迟到的 L1 删除移除。 */
    @Test
    void delayedInvalidationPreservesConcurrentWrite() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        cache.putVersioned("key", "old", 1).toCompletableFuture().join();
        var invalidation = cache.invalidateIfVersion("key", 1).toCompletableFuture();
        var write = cache.putVersioned("key", "new", 2).toCompletableFuture();
        assertFalse(write.isDone());
        store.invalidation.complete(true);
        assertTrue(invalidation.join());
        write.join();
        assertEquals(Optional.of("new"), cache.get("key").toCompletableFuture().join());
        assertEquals(0, store.reads);
    }

    /** 仅本地部署也必须检查实体版本。 */
    @Test
    void localOnlyInvalidationChecksEntityVersion() {
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults());
        cache.putVersioned("key", "new", 2).toCompletableFuture().join();
        assertFalse(cache.invalidateIfVersion("key", 1).toCompletableFuture().join());
        assertEquals(Optional.of("new"), cache.get("key").toCompletableFuture().join());
        assertTrue(cache.invalidateIfVersion("key", 2).toCompletableFuture().join());
        assertTrue(cache.get("key").toCompletableFuture().join().isEmpty());
    }

    /** 版本 0 的显式普通写仍可替换带实体版本条目，并重置其失效版本语义。 */
    @Test
    void unversionedWriteKeepsOrdinaryPutSemantics() {
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults());
        cache.putVersioned("key", "versioned", 2).toCompletableFuture().join();
        cache.put("key", "plain").toCompletableFuture().join();
        assertEquals(Optional.of("plain"), cache.get("key").toCompletableFuture().join());
        assertTrue(cache.invalidateIfVersion("key", 0).toCompletableFuture().join());
    }

    /** 真实服务配置的 loader 超时不得被迟到完成覆盖。 */
    @Test
    void timedOutLoaderCannotBackfillEitherCache() throws Exception {
        CachePolicy policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofSeconds(1),
                Duration.ZERO, 8, 1, false, Duration.ofMillis(50));
        var cache = new LayeredCacheService<String, String>(policy);
        var late = new CompletableFuture<Optional<String>>();
        var loaded = cache.getOrLoad("key", key -> late).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> loaded.get(2, TimeUnit.SECONDS));
        cache.put("key", "new").toCompletableFuture().join();
        late.complete(Optional.of("old"));
        assertEquals(Optional.of("new"), cache.get("key").toCompletableFuture().join());
        var local = new InMemoryCacheService<String, String>(policy);
        var lateLocal = new CompletableFuture<Optional<String>>();
        var localLoad = local.getOrLoad("key", key -> lateLocal).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> localLoad.get(2, TimeUnit.SECONDS));
        local.put("key", "new").toCompletableFuture().join();
        lateLocal.complete(Optional.of("old"));
        assertEquals(Optional.of("new"), local.get("key").toCompletableFuture().join());
    }

    /** 回填观察超时不能提前取消物理完成屏障；迟到写必须先结算，再执行失效。 */
    @Test
    void timedOutBackfillIsAbandonedBeforeItCanResurrectCache() throws Exception {
        CachePolicy policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofSeconds(1),
                Duration.ZERO, 8, 1, false, Duration.ofMillis(50));
        DelayedWriteStore store = new DelayedWriteStore();
        var cache = new LayeredCacheService<String, String>(policy, store);
        var loaded = cache.getOrLoad("key", CacheLoader.sync(key -> Optional.of("late"))).toCompletableFuture();
        assertTrue(store.writeStarted.await(2, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> loaded.get(2, TimeUnit.SECONDS));
        assertFalse(store.writeResult.isCancelled());
        var invalidation = cache.invalidate("key").toCompletableFuture();
        assertFalse(invalidation.isDone());
        store.current = store.pending;
        store.writeResult.complete(true);
        invalidation.get(2, TimeUnit.SECONDS);
        assertTrue(cache.get("key").toCompletableFuture().join().isEmpty());
    }

    /** L2 故障进入冷却窗口时不得把读取降级为 loader miss。 */
    @Test
    void l2ReadFailureOpensCooldownAndStopsDatabaseFallback() {
        Store store = new Store();
        store.failReads = true;
        var cache = new LayeredCacheService<String, String>(
                CachePolicy.defaults(), store, false, Duration.ofSeconds(30));

        assertThrows(ExecutionException.class,
                () -> cache.getOrLoad("key", CacheLoader.sync(key -> Optional.of("database")))
                        .toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(cache.l2CircuitOpen());
        assertEquals(0, store.loaderCalls);
        assertThrows(ExecutionException.class,
                () -> cache.getOrLoad("key", CacheLoader.sync(key -> Optional.of("database")))
                        .toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(1, cache.l2CircuitRejectedCount());
    }

    /** 提供可控失效结果，读取计数用于证明 L1 未丢失。 */
    private static final class Store implements CacheStore<String, String> {
        /** 延迟的失效结果。 */
        private final CompletableFuture<Boolean> invalidation = new CompletableFuture<>();
        /** 后端读取次数。 */
        private int reads;
        /** 模拟后端读取故障。 */
        private boolean failReads;
        /** loader 调用计数。 */
        private int loaderCalls;
        /** 返回缺失并计数。 */
        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(final String key) {
            reads++;
            if (failReads) return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        /** 确认测试写入。 */
        @Override public CompletionStage<Void> put(final String key, final CacheStoreEntry<String> entry) {
            return CompletableFuture.completedFuture(null);
        }
        /** 确认测试条件写入。 */
        @Override public CompletionStage<Boolean> putIfVersion(final String key, final CacheStoreEntry<String> entry) {
            return CompletableFuture.completedFuture(true);
        }
        /** 确认无条件删除。 */
        @Override public CompletionStage<Void> invalidate(final String key) {
            return CompletableFuture.completedFuture(null);
        }
        /** 返回延迟结果。 */
        @Override public CompletionStage<Boolean> invalidateIfVersion(final String key, final long version) {
            return invalidation;
        }
    }

    /** 模拟已发出且不能由观察 future 取消的物理写入。 */
    private static final class DelayedWriteStore implements CacheStore<String, String> {
        /** 写入开始闩锁。 */
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        /** 可取消的后端写入。 */
        private final CompletableFuture<Boolean> writeResult = new CompletableFuture<>();
        /** 已提交的条目。 */
        private CacheStoreEntry<String> current;
        /** 后端尚未确认的条目。 */
        private CacheStoreEntry<String> pending;

        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(final String key) {
            return CompletableFuture.completedFuture(Optional.ofNullable(current));
        }

        @Override public CompletionStage<Void> put(final String key, final CacheStoreEntry<String> entry) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<Boolean> putIfVersion(
                final String key, final CacheStoreEntry<String> entry) {
            pending = entry;
            writeStarted.countDown();
            return writeResult;
        }

        @Override public CompletionStage<Void> invalidate(final String key) {
            current = null;
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<Boolean> invalidateIfVersion(final String key, final long version) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
