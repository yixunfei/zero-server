package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 队列满、旧回填重试与重启版本三个真实故障边界。 @author zn */
@Timeout(10)
class CacheWriteBackReliabilityTest {
    /** 未读取 L2 的新服务也必须能推进已有高版本缓存。 */
    @Test void restartedServiceAdvancesBackendVersionBeforeDirectPut() {
        var store = new Store();
        store.values.put("k", new CacheStoreEntry<>("old", 9000, 0, Instant.now().plusSeconds(60), false));
        var restarted = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        restarted.put("k", "new").toCompletableFuture().join();
        assertEquals("new", store.values.get("k").value());
        assertEquals(9001, store.values.get("k").cacheVersion());
    }

    /** 失败回填在显式失效之后重试，不能重新创建已经删除的值。 */
    @Test void retryCannotResurrectInvalidatedValue() {
        var store = new Store(); store.fail = true;
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        cache.getOrLoad("k", CacheLoader.sync(key -> Optional.of("old"))).toCompletableFuture().join();
        store.fail = false;
        cache.invalidate("k").toCompletableFuture().join();
        assertEquals(0, cache.retryWriteBacks(10).toCompletableFuture().join());
        assertFalse(store.values.containsKey("k"));
        assertEquals(0, cache.healthSnapshot().backlogCount());
    }

    /** 超过 1024 个不同键后明确拒绝，已有最旧条目仍可补偿写入。 */
    @Test void saturationDoesNotSilentlyEvictOldestWrite() {
        var store = new Store(); store.fail = true;
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        for (int i = 0; i < 1024; i++) cache.getOrLoad("k" + i, CacheLoader.sync(key -> Optional.of(key)))
                .toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> cache.getOrLoad("overflow", CacheLoader.sync(key -> Optional.of(key)))
                .toCompletableFuture().join());
        assertEquals(1, cache.writeBackRejectedCount());
        assertEquals(1024, cache.healthSnapshot().backlogCount());
        store.fail = false;
        assertEquals(1024, cache.retryWriteBacks(Integer.MAX_VALUE).toCompletableFuture().join());
        assertTrue(store.values.containsKey("k0")); assertFalse(store.values.containsKey("overflow"));
    }

    /** 同键多个失败回填只占一项，补偿最新观察值。 */
    @Test void failedBackfillsCoalesceByKey() {
        var store = new Store(); store.fail = true;
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        cache.getOrLoad("k", CacheLoader.sync(key -> Optional.of("first"))).toCompletableFuture().join();
        cache.getOrLoad("k", CacheLoader.sync(key -> Optional.of("second"))).toCompletableFuture().join();
        assertEquals(1, cache.healthSnapshot().backlogCount());
        store.fail = false;
        assertEquals(1, cache.retryWriteBacks(10).toCompletableFuture().join());
        assertEquals("second", store.values.get("k").value());
    }

    /** 可控条件写后端。 */
    private static final class Store implements CacheStore<String, String> {
        /** 测试线程中的后端数据。 */
        private final Map<String, CacheStoreEntry<String>> values = new HashMap<>();
        /** 写故障开关。 */
        private boolean fail;
        /** @return 当前值。 */
        public CompletionStage<Optional<CacheStoreEntry<String>>> get(String key) { return CompletableFuture.completedFuture(Optional.ofNullable(values.get(key))); }
        /** @return 保存完成。 */
        public CompletionStage<Void> put(String key, CacheStoreEntry<String> entry) { values.put(key, entry); return CompletableFuture.completedFuture(null); }
        /** @return 原子单调版本比较结果。 */
        public synchronized CompletionStage<Boolean> putIfVersion(String key, CacheStoreEntry<String> entry) {
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("backend unavailable"));
            var previous = values.get(key);
            if (previous != null && previous.cacheVersion() >= entry.cacheVersion()) return CompletableFuture.completedFuture(false);
            values.put(key, entry); return CompletableFuture.completedFuture(true);
        }
        /** @return 失效完成。 */
        public CompletionStage<Void> invalidate(String key) { values.remove(key); return CompletableFuture.completedFuture(null); }
        /** @return 条件失效完成。 */
        public CompletionStage<Boolean> invalidateIfVersion(String key, long version) { values.remove(key); return CompletableFuture.completedFuture(true); }
    }
}
