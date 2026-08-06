package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 分层缓存服务测试。
 *
 * @author zn
 */
class LayeredCacheServiceTest {

    /**
     * 验证 L2 命中后会回填 L1。
     */
    @Test
    void layeredCacheShouldReadFromL2AndBackfillL1() {
        FakeStore<String, String> store = new FakeStore<>();
        store.entries.put("profile-1", new CacheStoreEntry<>(
                "redis-value",
                1L,
                1L,
                Instant.now().plusSeconds(60),
                false));
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(
                testPolicy(),
                store);

        Optional<String> first = cache.get("profile-1").toCompletableFuture().join();
        Optional<String> second = cache.get("profile-1").toCompletableFuture().join();

        assertEquals(Optional.of("redis-value"), first);
        assertEquals(Optional.of("redis-value"), second);
        assertEquals(1, store.readCount.get());
    }

    /**
     * 验证 loader singleflight 只加载一次，并写入 L2。
     */
    @Test
    void layeredCacheShouldSingleflightLoaderAndWriteL2() {
        FakeStore<String, String> store = new FakeStore<>();
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), store);
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(key -> {
            loadCount.incrementAndGet();
            return Optional.of("loaded-" + key);
        });

        Optional<String> first = cache.getOrLoad("scene-1", loader).toCompletableFuture().join();
        Optional<String> second = cache.getOrLoad("scene-1", loader).toCompletableFuture().join();

        assertEquals(Optional.of("loaded-scene-1"), first);
        assertEquals(Optional.of("loaded-scene-1"), second);
        assertEquals(1, loadCount.get());
        assertTrue(store.entries.containsKey("scene-1"));
    }

    /**
     * 验证 L2 失败时进入降级，但仍可通过 loader 回源。
     */
    @Test
    void layeredCacheShouldDegradeAndStillLoadWhenL2Fails() {
        FakeStore<String, String> store = new FakeStore<>();
        store.failRead = true;
        store.failWrite = true;
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), store);

        Optional<String> value = cache.getOrLoad("player-2", CacheLoader.sync(key -> Optional.of("local")))
                .toCompletableFuture()
                .join();

        assertEquals(Optional.of("local"), value);
        assertTrue(cache.degraded());
        assertTrue(cache.healthSnapshot().backendFailureCount() >= 1L);
    }

    /**
     * 验证负缓存阻止穿透。
     */
    @Test
    void layeredCacheShouldCacheNegativeResult() {
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), new FakeStore<>());
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(key -> {
            loadCount.incrementAndGet();
            return Optional.empty();
        });

        Optional<String> first = cache.getOrLoad("missing", loader).toCompletableFuture().join();
        Optional<String> second = cache.getOrLoad("missing", loader).toCompletableFuture().join();

        assertFalse(first.isPresent());
        assertFalse(second.isPresent());
        assertEquals(1, loadCount.get());
    }

    private CachePolicy testPolicy() {
        return new CachePolicy(
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ZERO,
                128,
                32,
                false);
    }

    /**
     * 测试用缓存存储。
     *
     * @param <K> 缓存键类型。
     * @param <V> 缓存值类型。
     * @author zn
     */
    private static final class FakeStore<K, V> implements CacheStore<K, V> {

        /**
         * 条目表。
         */
        private final Map<K, CacheStoreEntry<V>> entries = new ConcurrentHashMap<>();

        /**
         * 读取次数。
         */
        private final AtomicInteger readCount = new AtomicInteger();

        /**
         * 是否读取失败。
         */
        private boolean failRead;

        /**
         * 是否写入失败。
         */
        private boolean failWrite;

        /**
         * 读取缓存。
         *
         * @param key 缓存键；不可为空。
         * @return 缓存条目；不可为空。
         */
        @Override
        public CompletionStage<Optional<CacheStoreEntry<V>>> get(final K key) {
            readCount.incrementAndGet();
            if (failRead) {
                return CompletableFuture.failedFuture(new IllegalStateException("read failed"));
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(entries.get(key)));
        }

        /**
         * 写入缓存。
         *
         * @param key 缓存键；不可为空。
         * @param entry 缓存条目；不可为空。
         * @return 写入完成信号；不可为空。
         */
        @Override
        public CompletionStage<Void> put(final K key, final CacheStoreEntry<V> entry) {
            entries.put(key, entry);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 按版本写入缓存。
         *
         * @param key 缓存键；不可为空。
         * @param entry 缓存条目；不可为空。
         * @return true 表示成功；不可为空。
         */
        @Override
        public CompletionStage<Boolean> putIfVersion(final K key, final CacheStoreEntry<V> entry) {
            if (failWrite) {
                return CompletableFuture.failedFuture(new IllegalStateException("write failed"));
            }
            entries.put(key, entry);
            return CompletableFuture.completedFuture(true);
        }

        /**
         * 失效缓存。
         *
         * @param key 缓存键；不可为空。
         * @return 失效完成信号；不可为空。
         */
        @Override
        public CompletionStage<Void> invalidate(final K key) {
            entries.remove(key);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 按版本失效缓存。
         *
         * @param key 缓存键；不可为空。
         * @param entityVersion 实体版本；必须大于等于 0。
         * @return true 表示成功；不可为空。
         */
        @Override
        public CompletionStage<Boolean> invalidateIfVersion(final K key, final long entityVersion) {
            entries.remove(key);
            return CompletableFuture.completedFuture(true);
        }
    }
}
