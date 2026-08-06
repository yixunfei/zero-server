package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.cache.CacheStoreEntry;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.error.ZeroException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/**
 * Redis 缓存 store 外部集成测试。
 *
 * @author zn
 */
class RedisCacheStoreExternalIT {

    /**
     * 验证真实 Redis L2 缓存的读写、版本写入和条件失效。
     */
    @Test
    void redisCacheStoreShouldUseRealRedisAndVersionScripts() {
        RedisDriverSettings settings = RedisDriverSettings.fromSystemProperties();
        RedisDataAdapter adapter = new RedisDataAdapter();
        try (RedisClient client = adapter.createClient(settings)) {
            RedisCacheHealthCheck healthCheck = new RedisCacheHealthCheck(client);
            assertTrue(healthCheck.check(), "Redis external service is unavailable");
            client.flushDB();
            RedisCacheStore<String, String> store = new RedisCacheStore<>(
                    client,
                    "game",
                    "profile",
                    new StringCacheValueCodec());

            boolean firstSaved = store.putIfVersion("player-1", entry("v1", 1L)).toCompletableFuture().join();
            Optional<CacheStoreEntry<String>> first = store.get("player-1").toCompletableFuture().join();
            boolean secondSaved = store.putIfVersion("player-1", entry("v2", 2L)).toCompletableFuture().join();
            boolean staleSaved = store.putIfVersion("player-1", entry("stale", 1L)).toCompletableFuture().join();
            boolean oldInvalidate = store.invalidateIfVersion("player-1", 1L).toCompletableFuture().join();
            Optional<CacheStoreEntry<String>> afterOldInvalidate = store.get("player-1").toCompletableFuture().join();
            boolean currentInvalidate = store.invalidateIfVersion("player-1", 2L).toCompletableFuture().join();
            Optional<CacheStoreEntry<String>> afterCurrentInvalidate = store.get("player-1").toCompletableFuture().join();

            assertTrue(firstSaved);
            assertEquals(Optional.of("v1"), first.flatMap(CacheStoreEntry::optionalValue));
            assertTrue(secondSaved);
            assertFalse(staleSaved);
            assertFalse(oldInvalidate);
            assertEquals(Optional.of("v2"), afterOldInvalidate.flatMap(CacheStoreEntry::optionalValue));
            assertTrue(currentInvalidate);
            assertFalse(afterCurrentInvalidate.isPresent());
            assertTrue(store.valueKey("player-1").startsWith("zero:cache:"));
            assertTrue(store.valueKey("player-1").endsWith(":k1:str:cGxheWVyLTE"));
            assertFalse(store.valueKey("player-1").startsWith("zero:data:"));
        }
    }

    private CacheStoreEntry<String> entry(final String value, final long entityVersion) {
        return new CacheStoreEntry<>(
                value,
                entityVersion,
                entityVersion,
                Instant.now().plusSeconds(60),
                false);
    }

    /**
     * 字符串缓存值 codec。
     *
     * @author zn
     */
    private static final class StringCacheValueCodec implements CacheValueCodec<String> {

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空。
         */
        @Override
        public String name() {
            return "string";
        }

        /**
         * 编码字符串。
         *
         * @param value 字符串；不可为空。
         * @return 字节；不可为空。
         */
        @Override
        public byte[] encode(final String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 解码字符串。
         *
         * @param bytes 字节；不可为空。
         * @return 字符串；不可为空。
         */
        @Override
        public String decode(final byte[] bytes) {
            try {
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (RuntimeException ex) {
                throw ZeroException.of(CacheErrorCode.DESERIALIZE_FAILED, "decode failed", ex);
            }
        }
    }
}
