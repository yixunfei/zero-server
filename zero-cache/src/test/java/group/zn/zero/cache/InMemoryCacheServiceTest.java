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
}
