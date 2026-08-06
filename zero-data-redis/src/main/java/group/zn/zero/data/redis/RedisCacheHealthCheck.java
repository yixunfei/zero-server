package group.zn.zero.data.redis;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Objects;
import redis.clients.jedis.RedisClient;

/**
 * Redis 缓存适配器健康检查。
 *
 * <p>该检查用于 Redis L2 cache store 的显式可用性探测。Jedis client 是同步驱动；
 * 生产环境应由调用方在 Actor 线程之外执行该检查。
 *
 * @author zn
 */
public final class RedisCacheHealthCheck {

    /**
     * Redis client。
     */
    private final RedisClient client;

    /**
     * 创建 Redis 缓存健康检查。
     *
     * @param client Redis client；不可为空。
     * @throws NullPointerException 当 Redis client 为空时抛出。
     */
    public RedisCacheHealthCheck(final RedisClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 执行健康检查。
     *
     * @return true 表示 Redis cache 后端可用；线程安全性由 Jedis 保证。
     */
    public boolean check() {
        try {
            checkOrThrow();
            return true;
        } catch (ZeroException ex) {
            return false;
        }
    }

    /**
     * 执行健康检查，失败时抛出统一缓存异常。
     *
     * @throws ZeroException Redis cache 后端不可用时抛出，绑定 `CacheErrorCode.BACKEND_UNAVAILABLE`。
     */
    public void checkOrThrow() {
        try {
            String pong = client.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw ZeroException.of(CacheErrorCode.BACKEND_UNAVAILABLE, "redis cache ping failed: " + pong, null);
            }
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(CacheErrorCode.BACKEND_UNAVAILABLE, "redis cache backend is unavailable", ex);
        }
    }
}
