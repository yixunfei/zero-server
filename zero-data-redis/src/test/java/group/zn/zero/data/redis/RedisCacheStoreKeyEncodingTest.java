package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.error.ZeroException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/**
 * Redis 缓存 store key 编码测试。
 *
 * @author zn
 */
class RedisCacheStoreKeyEncodingTest {

    /**
     * 验证默认 Redis cache store 使用 k1 key 格式。
     */
    @Test
    void storeShouldUseDefaultK1KeyCodec() {
        try (RedisClient client = RedisClient.create("redis://127.0.0.1:1/0")) {
            RedisCacheStore<String, String> store = new RedisCacheStore<>(
                    client,
                    "game",
                    "profile",
                    new StringCacheValueCodec());

            String valueKey = store.valueKey("player-1");

            assertTrue(valueKey.startsWith("zero:cache:{game:profile:"));
            assertTrue(valueKey.endsWith(":k1:str:cGxheWVyLTE"));
        }
    }

    /**
     * 验证不支持的普通对象 key 会直接失败。
     */
    @Test
    void unsupportedKeyShouldFailFast() {
        try (RedisClient client = RedisClient.create("redis://127.0.0.1:1/0")) {
            RedisCacheStore<Object, String> store = new RedisCacheStore<>(
                    client,
                    "game",
                    "profile",
                    new StringCacheValueCodec());

            ZeroException exception = assertThrows(ZeroException.class, () -> store.valueKey(new Object()));

            assertEquals(CacheErrorCode.INVALID_KEY, exception.errorCode());
        }
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
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
