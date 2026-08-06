package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheLoader;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Redis 分布式缓存服务测试。
 *
 * @author zn
 */
class RedisDistributedCacheServiceTest {

    /**
     * 验证无 Redis store 时退化为本地缓存。
     */
    @Test
    void serviceShouldFallbackToLocalCacheWithoutRedisStore() {
        RedisDistributedCacheService<String, String> service = new RedisDistributedCacheService<>(
                "redis-test",
                Duration.ofMinutes(1),
                Duration.ofMinutes(1));
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(key -> {
            loadCount.incrementAndGet();
            return Optional.of("value-" + key);
        });

        Optional<String> first = service.getOrLoad("profile-1", loader).toCompletableFuture().join();
        Optional<String> second = service.getOrLoad("profile-1", loader).toCompletableFuture().join();

        assertEquals(Optional.of("value-profile-1"), first);
        assertEquals(Optional.of("value-profile-1"), second);
        assertEquals(1, loadCount.get());
        assertFalse(service.degraded());
    }

    /**
     * 验证降级计数和积压计数。
     */
    @Test
    void serviceShouldExposeDegradedSignals() {
        RedisDistributedCacheService<String, String> service = new RedisDistributedCacheService<>();

        service.markWriteBackFailure();
        service.addBacklog(3L);

        assertTrue(service.degraded());
        assertEquals(1L, service.writeBackFailureCount());
        assertEquals(3L, service.backlogCount());
        assertEquals(0L, service.clearBacklog());
    }
}
