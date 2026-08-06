package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/**
 * Redis 缓存健康检查测试。
 *
 * @author zn
 */
class RedisCacheHealthCheckTest {

    /**
     * 验证 Redis 不可用时健康检查返回 false，并绑定缓存错误码。
     */
    @Test
    void healthCheckShouldExposeCacheErrorWhenRedisUnavailable() {
        try (RedisClient client = RedisClient.create("redis://127.0.0.1:1/0")) {
            RedisCacheHealthCheck healthCheck = new RedisCacheHealthCheck(client);

            ZeroException exception = assertThrows(ZeroException.class, healthCheck::checkOrThrow);

            assertFalse(healthCheck.check());
            assertEquals(CacheErrorCode.BACKEND_UNAVAILABLE, exception.errorCode());
        }
    }
}
