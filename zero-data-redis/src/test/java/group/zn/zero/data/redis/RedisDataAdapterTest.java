package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheLoader;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Redis 数据与缓存适配器测试。
 *
 * @author zn
 */
class RedisDataAdapterTest {

    /**
     * 验证 Redis 数据适配器提供追加式存储模式入口。
     */
    @Test
    void adapterShouldExposeAppendOnlyMode() {
        RedisDataAdapter adapter = new RedisDataAdapter();
        adapter.appendOnlyMode(true);

        assertEquals("redis", adapter.serviceName());
        assertTrue(adapter.appendOnlyMode());
    }

    /**
     * 验证 Redis 分布式缓存 fallback 可以自动加载并标记降级。
     */
    @Test
    void distributedCacheShouldLoadAndExposeDegradeSignals() {
        RedisDistributedCacheService<String, String> cache = new RedisDistributedCacheService<>();

        Optional<String> loaded = cache.getOrLoad(
                "rank:1",
                CacheLoader.sync(key -> Optional.of("value-" + key))).toCompletableFuture().join();
        cache.markWriteBackFailure();
        cache.addBacklog(2);

        assertEquals(Optional.of("value-rank:1"), loaded);
        assertEquals(1L, cache.statistics().loadCount());
        assertEquals(1L, cache.writeBackFailureCount());
        assertEquals(2L, cache.backlogCount());
        assertTrue(cache.degraded());
    }
}
