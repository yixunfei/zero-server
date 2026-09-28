package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheLoader;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheStore;
import group.zn.zero.cache.CacheStoreEntry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Redis 分布式缓存服务测试。
 *
 * @author zn
 */
class RedisDistributedCacheServiceTest {

    /** Redis 门面默认不回填失败结果，显式降级选项传递给分层缓存。 */
    @Test void backendFailureFallbackIsExplicit() {
        for (boolean fallback : new boolean[]{false, true}) {
            var service = fallback
                    ? new RedisDistributedCacheService<String, String>("redis", CachePolicy.defaults(), new FailingStore(), true)
                    : new RedisDistributedCacheService<String, String>("redis", CachePolicy.defaults(), new FailingStore());
            assertEquals(Optional.of("loaded"), service.getOrLoad("p", CacheLoader.sync(key -> Optional.of("loaded")))
                    .toCompletableFuture().join());
            assertEquals(fallback ? Optional.of("loaded") : Optional.empty(), service.get("p").toCompletableFuture().join());
            assertTrue(service.degraded());
            assertEquals(1, service.backlogCount());
        }
    }

    /** 只读未命中、写回失败的测试后端。 */
    private static final class FailingStore implements CacheStore<String, String> {
        /** @return 空的异步结果。 */
        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(String key) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        /** @return 模拟后端异常。 */
        @Override public CompletionStage<Void> put(String key, CacheStoreEntry<String> entry) {
            return CompletableFuture.failedFuture(new IllegalStateException("backend unavailable"));
        }
        /** @return 模拟后端异常。 */
        @Override public CompletionStage<Boolean> putIfVersion(String key, CacheStoreEntry<String> entry) {
            return CompletableFuture.failedFuture(new IllegalStateException("backend unavailable"));
        }
        /** @return 本测试无需的失效结果。 */
        @Override public CompletionStage<Void> invalidate(String key) { return CompletableFuture.completedFuture(null); }
        /** @return 本测试无需的条件失效结果。 */
        @Override public CompletionStage<Boolean> invalidateIfVersion(String key, long version) {
            return CompletableFuture.completedFuture(true);
        }
    }

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
