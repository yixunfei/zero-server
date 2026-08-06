package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Redis 缓存 key 策略测试。
 *
 * @author zn
 */
class DefaultRedisCacheKeyStrategyTest {

    /**
     * 验证 cache key 与 data/journal key 前缀隔离。
     */
    @Test
    void cacheKeysShouldUseDedicatedNamespace() {
        DefaultRedisCacheKeyStrategy strategy = new DefaultRedisCacheKeyStrategy(16);

        String encodedKey = "k1:str:cGxheWVyLTE";
        String valueKey = strategy.valueKey("game", "player-profile", encodedKey);
        String versionKey = strategy.versionKey("game", "player-profile", encodedKey);
        String indexKey = strategy.indexKey("game", "player-profile");

        assertTrue(valueKey.startsWith("zero:cache:"));
        assertTrue(versionKey.startsWith("zero:cachever:"));
        assertTrue(indexKey.startsWith("zero:cacheidx:"));
        assertTrue(valueKey.endsWith(":k1:str:cGxheWVyLTE"));
        assertFalse(valueKey.startsWith("zero:data:"));
        assertFalse(valueKey.startsWith("zero:index:"));
        assertFalse(valueKey.startsWith("zero:journal:"));
    }

    /**
     * 验证非法 segment 会被拒绝。
     */
    @Test
    void cacheKeysShouldRejectInvalidSegments() {
        DefaultRedisCacheKeyStrategy strategy = new DefaultRedisCacheKeyStrategy(16);

        assertThrows(IllegalArgumentException.class, () -> strategy.valueKey("game", "cache", "{bad}"));
        assertThrows(IllegalArgumentException.class, () -> strategy.valueKey(" ", "cache", "key"));
    }
}
