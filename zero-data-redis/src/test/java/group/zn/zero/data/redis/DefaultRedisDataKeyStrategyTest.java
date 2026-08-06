package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 默认 Redis 数据 key 策略测试。
 *
 * @author zn
 */
class DefaultRedisDataKeyStrategyTest {

    /**
     * 验证对象、索引和追加日志 key 共享同一个 bucket hash tag。
     */
    @Test
    void keysShouldShareBucketHashTag() {
        DefaultRedisDataKeyStrategy strategy = new DefaultRedisDataKeyStrategy(100);
        String bucket = strategy.bucketLabel("1001");
        String tag = "{game:player:" + bucket + "}";

        assertEquals("zero:data:" + tag + ":1001", strategy.dataKey("game", "player", "1001"));
        assertEquals("zero:index:" + tag + ":ids", strategy.indexKey("game", "player", "1001"));
        assertEquals("zero:journal:" + tag + ":stream", strategy.journalKey("game", "player", "1001"));
    }

    /**
     * 验证不同 ID 可以被分散到不同 bucket。
     */
    @Test
    void bucketShouldBeStableAndReadable() {
        DefaultRedisDataKeyStrategy strategy = new DefaultRedisDataKeyStrategy(128);

        assertEquals(strategy.bucketLabel("player-1"), strategy.bucketLabel("player-1"));
        assertTrue(strategy.bucketLabel("player-1").length() >= 2);
    }

    /**
     * 验证非法 key 片段会被拒绝。
     */
    @Test
    void keySegmentsShouldRejectBlankAndHashTagBrace() {
        DefaultRedisDataKeyStrategy strategy = new DefaultRedisDataKeyStrategy();

        assertThrows(IllegalArgumentException.class, () -> strategy.dataKey("game", "player", ""));
        assertThrows(IllegalArgumentException.class, () -> strategy.dataKey("game", "{player}", "1001"));
    }
}
