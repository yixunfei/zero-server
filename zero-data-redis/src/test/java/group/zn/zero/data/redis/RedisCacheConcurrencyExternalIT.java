package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheStoreEntry;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.cache.LayeredCacheService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/** 真实 Redis 条件写回归；每例使用独立键，只清理自身数据。 @author zn */
class RedisCacheConcurrencyExternalIT {
    /** 未版本化业务缓存跨实例更新时使用 cacheVersion 保证单调写入。 */
    @Test void restartedLayeredCacheCanUpdateUnversionedValue() {
        try (RedisClient client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = store(client);
            String key = UUID.randomUUID().toString();
            try {
                var first = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
                first.put(key, "old").toCompletableFuture().join();
                var restarted = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
                restarted.put(key, "new").toCompletableFuture().join();
                assertEquals("new", store.get(key).toCompletableFuture().join().orElseThrow().value());
                assertEquals(2, store.get(key).toCompletableFuture().join().orElseThrow().cacheVersion());
            } finally {
                store.invalidate(key).toCompletableFuture().join();
            }
        }
    }

    /** 超过 double 精度的相邻缓存版本仍可区分，同版或旧版不能覆盖胜出值。 */
    @Test void unversionedCacheComparisonPreservesAllLongBitsAndEntityFence() {
        try (RedisClient client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = store(client);
            String key = UUID.randomUUID().toString();
            long version = 9_007_199_254_740_992L;
            try {
                assertTrue(store.putIfVersion(key, entry("old", version, 0)).toCompletableFuture().join());
                assertTrue(store.putIfVersion(key, entry("new", version + 1, 0)).toCompletableFuture().join());
                assertFalse(store.putIfVersion(key, entry("equal", version + 1, 0)).toCompletableFuture().join());
                assertFalse(store.putIfVersion(key, entry("stale", version, 0)).toCompletableFuture().join());
                assertTrue(store.putIfVersion(key, entry("entity", 1, 2)).toCompletableFuture().join());
                assertFalse(store.putIfVersion(key, entry("unversioned", Long.MAX_VALUE, 0)).toCompletableFuture().join());
                assertEquals("entity", store.get(key).toCompletableFuture().join().orElseThrow().value());
            } finally {
                store.invalidate(key).toCompletableFuture().join();
            }
        }
    }

    private static RedisCacheStore<String, String> store(final RedisClient client) {
        return new RedisCacheStore<>(client, "concurrency-audit", "cache-version", new StringCodec());
    }

    private static CacheStoreEntry<String> entry(final String value, final long cacheVersion, final long entityVersion) {
        return new CacheStoreEntry<>(value, cacheVersion, entityVersion, Instant.now().plusSeconds(60), false);
    }

    /** 测试字符串编码器；不可变，可并发调用。 */
    private static final class StringCodec implements CacheValueCodec<String> {
        /** @return 稳定编码器名称。 */
        @Override public String name() { return "string"; }
        /** @return UTF-8 编码的新数组。 */
        @Override public byte[] encode(final String value) { return value.getBytes(StandardCharsets.UTF_8); }
        /** @return UTF-8 解码值。 */
        @Override public String decode(final byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    }
}
