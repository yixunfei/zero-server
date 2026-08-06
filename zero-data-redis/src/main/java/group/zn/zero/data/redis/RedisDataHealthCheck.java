package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.Objects;
import redis.clients.jedis.RedisClient;

/**
 * Redis 数据适配器健康检查。
 *
 * @author zn
 */
public final class RedisDataHealthCheck {

    /**
     * Redis client。
     */
    private final RedisClient client;

    /**
     * 创建 Redis 数据适配器健康检查。
     *
     * @param client Redis client；不可为空。
     * @throws NullPointerException 当 Redis client 为空时抛出。
     */
    public RedisDataHealthCheck(final RedisClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 执行健康检查。
     *
     * @return true 表示 Redis 可用；线程安全性由 Jedis 保证。
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
     * 执行健康检查，失败时抛出统一异常。
     *
     * @throws ZeroException Redis 不可用时抛出，绑定 `DataErrorCode.BACKEND_UNAVAILABLE`。
     */
    public void checkOrThrow() {
        try {
            String pong = client.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE, "redis ping failed: " + pong, null);
            }
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE, "redis backend is unavailable", ex);
        }
    }
}
