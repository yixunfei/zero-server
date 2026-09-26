package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 用受控完成顺序验证失效、回源和写入之间的代际隔离。 @author zn */
class LayeredCacheMutationRaceTest {
    /** 失效完成后，先前读取到的 L2 值不可复活。 */
    @Test void oldReadCannotRefillAfterInvalidation() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        store.delayedRead = new CompletableFuture<>();
        var read = cache.get("k");
        cache.invalidate("k").toCompletableFuture().join();
        store.delayedRead.complete(Optional.of(entry("old", 1)));
        read.toCompletableFuture().join();
        store.delayedRead = null;
        assertTrue(cache.get("k").toCompletableFuture().join().isEmpty());
    }

    /** 显式写入必须使更早的正、负加载结果失去回填权。 */
    @Test void oldLoaderCannotReplaceExplicitWrite() {
        for (Optional<String> value : java.util.List.of(Optional.of("old"), Optional.<String>empty())) {
            Store store = new Store();
            var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
            var source = new CompletableFuture<Optional<String>>();
            var load = cache.getOrLoad("k", key -> source);
            cache.putVersioned("k", "new", 2).toCompletableFuture().join();
            source.complete(value);
            load.toCompletableFuture().join();
            assertEquals(Optional.of("new"), cache.get("k").toCompletableFuture().join());
            assertEquals("new", store.current.value());
        }
    }

    /** 失效之后的加载必须使用新一代 loader，而不是加入旧加载。 */
    @Test void invalidationDetachesOlderSingleflight() {
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults());
        var source = new CompletableFuture<Optional<String>>();
        var old = cache.getOrLoad("k", key -> source);
        cache.invalidate("k").toCompletableFuture().join();
        var fresh = cache.getOrLoad("k", CacheLoader.sync(key -> Optional.of("new"))).toCompletableFuture();
        assertTrue(fresh.isDone());
        source.complete(Optional.of("old"));
        old.toCompletableFuture().join();
        assertEquals(Optional.of("new"), fresh.join());
        assertEquals(Optional.of("new"), cache.get("k").toCompletableFuture().join());
    }

    /** L2 拒绝回填时，不能把被拒绝的值作为 L1 最新值。 */
    @Test void rejectedBackfillDoesNotPoisonLocalCache() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        var source = new CompletableFuture<Optional<String>>();
        var load = cache.getOrLoad("k", key -> source);
        store.current = entry("remote-new", 5);
        store.rejectWrite = true;
        source.complete(Optional.of("stale"));
        load.toCompletableFuture().join();
        assertEquals(Optional.of("remote-new"), cache.get("k").toCompletableFuture().join());
    }

    /** 已发出的异步回填必须在同 key 失效前结算，避免先删后写。 */
    @Test void invalidationWaitsForStartedBackfill() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        store.delayedWrite = new CompletableFuture<>();
        var load = cache.getOrLoad("k", CacheLoader.sync(key -> Optional.of("old")));
        var invalidation = cache.invalidate("k").toCompletableFuture();
        assertFalse(invalidation.isDone());
        store.delayedWrite.complete(true);
        load.toCompletableFuture().join();
        invalidation.join();
        assertTrue(cache.get("k").toCompletableFuture().join().isEmpty());
    }

    private static CacheStoreEntry<String> entry(String value, long version) {
        return new CacheStoreEntry<>(value, version, version, Instant.now().plusSeconds(60), false);
    }

    /** 内存存储，仅测试线程推进。 */
    private static final class Store implements CacheStore<String, String> {
        /** 已提交的后端条目。 */
        private CacheStoreEntry<String> current;
        /** 可控读。 */
        private CompletableFuture<Optional<CacheStoreEntry<String>>> delayedRead;
        /** 可控写。 */
        private CompletableFuture<Boolean> delayedWrite;
        /** 模拟其他实例赢得条件写。 */
        private boolean rejectWrite;
        /** 返回当前值或延迟读取。 */
        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(String key) {
            return delayedRead == null ? CompletableFuture.completedFuture(Optional.ofNullable(current)) : delayedRead;
        }
        /** 直接写入。 */
        @Override public CompletionStage<Void> put(String key, CacheStoreEntry<String> value) {
            current = value;
            return CompletableFuture.completedFuture(null);
        }
        /** 在测试指定时刻提交条件写。 */
        @Override public CompletionStage<Boolean> putIfVersion(String key, CacheStoreEntry<String> value) {
            if (rejectWrite) return CompletableFuture.completedFuture(false);
            if (delayedWrite != null) return delayedWrite.thenApply(saved -> { if (saved) current = value; return saved; });
            current = value;
            return CompletableFuture.completedFuture(true);
        }
        /** 删除当前值。 */
        @Override public CompletionStage<Void> invalidate(String key) {
            current = null;
            return CompletableFuture.completedFuture(null);
        }
        /** 测试用条件删除。 */
        @Override public CompletionStage<Boolean> invalidateIfVersion(String key, long version) {
            current = null;
            return CompletableFuture.completedFuture(true);
        }
    }
}
