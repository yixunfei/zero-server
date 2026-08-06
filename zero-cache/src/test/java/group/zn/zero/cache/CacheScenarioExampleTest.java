package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * S2C-03 缓存典型场景示例测试。
 *
 * @author zn
 */
class CacheScenarioExampleTest {

    /**
     * 验证玩家资料缓存采用 L1 + L2 + Repository loader 的 read-through 形态。
     */
    @Test
    void playerProfileShouldUseLoaderAndBackfillL2() {
        VersionedStore<String, String> store = new VersionedStore<>();
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), store);
        AtomicInteger repositoryLoadCount = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(playerId -> {
            repositoryLoadCount.incrementAndGet();
            return Optional.of("profile:" + playerId);
        });

        Optional<String> first = cache.getOrLoad("player-1001", loader).toCompletableFuture().join();
        Optional<String> second = cache.getOrLoad("player-1001", loader).toCompletableFuture().join();

        assertEquals(Optional.of("profile:player-1001"), first);
        assertEquals(Optional.of("profile:player-1001"), second);
        assertEquals(1, repositoryLoadCount.get());
        assertTrue(store.entries.containsKey("player-1001"));
    }

    /**
     * 验证场景热点数据在并发回源时只触发一次 loader，避免热点击穿。
     */
    @Test
    void sceneHotDataShouldSingleflightConcurrentLoads() {
        VersionedStore<String, String> store = new VersionedStore<>();
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), store);
        CompletableFuture<Optional<String>> repositoryFuture = new CompletableFuture<>();
        AtomicInteger repositoryLoadCount = new AtomicInteger();
        CacheLoader<String, String> loader = sceneId -> {
            repositoryLoadCount.incrementAndGet();
            return repositoryFuture;
        };

        CompletionStage<Optional<String>> first = cache.getOrLoad("scene-9001", loader);
        CompletionStage<Optional<String>> second = cache.getOrLoad("scene-9001", loader);
        repositoryFuture.complete(Optional.of("scene-state"));

        assertEquals(Optional.of("scene-state"), first.toCompletableFuture().join());
        assertEquals(Optional.of("scene-state"), second.toCompletableFuture().join());
        assertEquals(1, repositoryLoadCount.get());
        assertTrue(store.entries.containsKey("scene-9001"));
    }

    /**
     * 验证配置或活动缓存通过实体版本避免旧失效请求删除新缓存。
     */
    @Test
    void activityConfigShouldKeepNewVersionWhenStaleInvalidateArrives() {
        VersionedStore<String, String> store = new VersionedStore<>();
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), store);

        cache.putVersioned("activity:summer", "config-v2", 2L).toCompletableFuture().join();
        boolean staleInvalidated = cache.invalidateIfVersion("activity:summer", 1L)
                .toCompletableFuture()
                .join();
        Optional<String> afterStaleInvalidate = cache.get("activity:summer").toCompletableFuture().join();
        boolean currentInvalidated = cache.invalidateIfVersion("activity:summer", 2L)
                .toCompletableFuture()
                .join();
        Optional<String> afterCurrentInvalidate = cache.get("activity:summer").toCompletableFuture().join();

        assertFalse(staleInvalidated);
        assertEquals(Optional.of("config-v2"), afterStaleInvalidate);
        assertTrue(currentInvalidated);
        assertTrue(afterCurrentInvalidate.isEmpty());
    }

    /**
     * 验证 Redis L2 不可用时降级为 loader + L1，不伪装成分布式缓存命中。
     */
    @Test
    void redisDegradationShouldKeepLocalReadCapability() {
        VersionedStore<String, String> store = new VersionedStore<>();
        store.failRead = true;
        store.failWrite = true;
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(testPolicy(), store);

        Optional<String> first = cache.getOrLoad("player-2002", CacheLoader.sync(key -> Optional.of("fallback")))
                .toCompletableFuture()
                .join();
        Optional<String> second = cache.get("player-2002").toCompletableFuture().join();

        assertEquals(Optional.of("fallback"), first);
        assertEquals(Optional.of("fallback"), second);
        assertTrue(cache.degraded());
        assertTrue(cache.healthSnapshot().backendFailureCount() >= 1L);
    }

    private CachePolicy testPolicy() {
        return new CachePolicy(
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ZERO,
                256,
                32,
                false);
    }

    /**
     * 测试用版本化 L2 store。
     *
     * @param <K> 缓存键类型。
     * @param <V> 缓存值类型。
     * @author zn
     */
    private static final class VersionedStore<K, V> implements CacheStore<K, V> {

        /**
         * L2 条目表。
         */
        private final Map<K, CacheStoreEntry<V>> entries = new ConcurrentHashMap<>();

        /**
         * 是否模拟读取失败。
         */
        private boolean failRead;

        /**
         * 是否模拟写入失败。
         */
        private boolean failWrite;

        /**
         * 读取缓存条目。
         *
         * @param key 缓存键；不可为空。
         * @return 缓存条目；可为空；线程安全。
         */
        @Override
        public CompletionStage<Optional<CacheStoreEntry<V>>> get(final K key) {
            if (failRead) {
                return CompletableFuture.failedFuture(new IllegalStateException("read failed"));
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(entries.get(key)));
        }

        /**
         * 写入缓存条目。
         *
         * @param key 缓存键；不可为空。
         * @param entry 缓存条目；不可为空。
         * @return 写入完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> put(final K key, final CacheStoreEntry<V> entry) {
            entries.put(key, entry);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 按实体版本写入缓存条目。
         *
         * @param key 缓存键；不可为空。
         * @param entry 缓存条目；不可为空。
         * @return true 表示写入成功；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Boolean> putIfVersion(final K key, final CacheStoreEntry<V> entry) {
            if (failWrite) {
                return CompletableFuture.failedFuture(new IllegalStateException("write failed"));
            }
            CacheStoreEntry<V> current = entries.get(key);
            if (current != null && current.entityVersion() > entry.entityVersion()) {
                return CompletableFuture.completedFuture(false);
            }
            entries.put(key, entry);
            return CompletableFuture.completedFuture(true);
        }

        /**
         * 失效缓存条目。
         *
         * @param key 缓存键；不可为空。
         * @return 失效完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> invalidate(final K key) {
            entries.remove(key);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 按实体版本失效缓存条目。
         *
         * @param key 缓存键；不可为空。
         * @param entityVersion 实体版本；必须大于等于 0。
         * @return true 表示失效成功或无需失效；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Boolean> invalidateIfVersion(final K key, final long entityVersion) {
            CacheStoreEntry<V> current = entries.get(key);
            if (current == null) {
                return CompletableFuture.completedFuture(true);
            }
            if (current.entityVersion() > entityVersion) {
                return CompletableFuture.completedFuture(false);
            }
            entries.remove(key);
            return CompletableFuture.completedFuture(true);
        }
    }

}
