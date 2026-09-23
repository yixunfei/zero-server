package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 本地内存缓存服务测试。
 *
 * @author zn
 */
class InMemoryCacheServiceTest {

    /** 并发写入越过容量边界后恰好保留上限条目，更新条目排在写入顺序末端。 */
    @Test
    void concurrentWritersMaintainExactCapacity() throws Exception {
        CachePolicy policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ZERO, 8, 32, false);
        var cache = new InMemoryCacheService<Integer, String>(policy);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var writes = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 64; i++) {
                int key = i;
                writes.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    cache.put(key, "value").toCompletableFuture().join();
                }));
            }
            start.countDown();
            for (var write : writes) {
                write.get(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertEquals(8, cache.snapshot().size());
        for (int key = 100; key < 108; key++) {
            cache.put(key, "new").toCompletableFuture().join();
        }
        assertEquals(8, cache.snapshot().size());
        assertTrue(cache.snapshot().keySet().stream().allMatch(key -> key >= 100));
        cache.put(100, "updated").toCompletableFuture().join();
        cache.put(108, "last").toCompletableFuture().join();
        assertEquals(Optional.of("updated"), cache.get(100).toCompletableFuture().join());
        assertTrue(cache.get(101).toCompletableFuture().join().isEmpty());
    }

    /**
     * 验证自动加载、防击穿和版本号。
     */
    @Test
    void cacheShouldLoadOnceAndExposeVersion() {
        InMemoryCacheService<String, String> cache = new InMemoryCacheService<>(
                Duration.ofMinutes(1),
                Duration.ofMinutes(1));
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(key -> {
            loadCount.incrementAndGet();
            return Optional.of("value-" + key);
        });

        Optional<String> first = cache.getOrLoad("player-1", loader).toCompletableFuture().join();
        Optional<String> second = cache.getOrLoad("player-1", loader).toCompletableFuture().join();

        assertEquals(Optional.of("value-player-1"), first);
        assertEquals(Optional.of("value-player-1"), second);
        assertEquals(1, loadCount.get());
        assertEquals(Optional.of(1L), cache.versionOf("player-1"));
        assertEquals(1L, cache.statistics().loadCount());
        assertTrue(cache.statistics().hitCount() >= 1L);
    }

    /**
     * 验证负缓存可以阻断穿透并支持失效。
     */
    @Test
    void cacheShouldStoreNegativeEntryAndInvalidate() {
        InMemoryCacheService<String, String> cache = new InMemoryCacheService<>(
                Duration.ofMinutes(1),
                Duration.ofMinutes(1));
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(key -> {
            loadCount.incrementAndGet();
            return Optional.empty();
        });

        Optional<String> first = cache.getOrLoad("missing", loader).toCompletableFuture().join();
        Optional<String> second = cache.getOrLoad("missing", loader).toCompletableFuture().join();
        cache.invalidate("missing").toCompletableFuture().join();

        assertFalse(first.isPresent());
        assertFalse(second.isPresent());
        assertEquals(1, loadCount.get());
        assertTrue(cache.versionOf("missing").isEmpty());
        assertEquals(1L, cache.statistics().invalidateCount());
    }

    /** 并发写入超过容量时只保留最新容量内条目。 */
    @Test
    void cacheShouldEvictOldestEntryAtCapacity() {
        CachePolicy policy = new CachePolicy(
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ZERO, 2, 2, false);
        InMemoryCacheService<String, String> cache = new InMemoryCacheService<>(policy);

        cache.put("a", "a").toCompletableFuture().join();
        cache.put("b", "b").toCompletableFuture().join();
        cache.put("c", "c").toCompletableFuture().join();

        assertEquals(2, cache.snapshot().size());
        assertTrue(cache.get("b").toCompletableFuture().join().isPresent());
        assertTrue(cache.get("c").toCompletableFuture().join().isPresent());
    }
}
