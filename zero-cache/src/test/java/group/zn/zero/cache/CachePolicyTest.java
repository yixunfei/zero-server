package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 缓存策略测试。
 *
 * @author zn
 */
class CachePolicyTest {

    /**
     * 验证默认策略可用。
     */
    @Test
    void defaultPolicyShouldBeValid() {
        CachePolicy policy = CachePolicy.defaults();

        assertTrue(policy.ttl().isPositive());
        assertTrue(policy.negativeTtl().isPositive());
        assertTrue(policy.maxLocalEntries() > 0);
        assertTrue(policy.maxConcurrentLoads() > 0);
    }

    /**
     * 验证非法策略会被拒绝。
     */
    @Test
    void policyShouldRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new CachePolicy(
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ZERO,
                1,
                1,
                false));
        assertThrows(IllegalArgumentException.class, () -> new CachePolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(-1),
                1,
                1,
                false));
    }
}
