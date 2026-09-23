package group.zn.zero.runtime.redis;

import group.zn.zero.data.redis.RedisCacheHealthCheck;
import group.zn.zero.data.redis.RedisDataHealthCheck;
import group.zn.zero.data.redis.RedisDriverClientFactory;
import group.zn.zero.data.redis.RedisDriverSettings;
import java.time.Duration;
import redis.clients.jedis.RedisClient;

/** Startup probe with a temporary client bounded by the remaining startup budget. */
final class RedisStartupProbes {
    private RedisStartupProbes() {
    }

    static void data(final RedisDriverSettings settings, final Duration timeout) {
        try (RedisClient client = RedisDriverClientFactory.create(settings, timeout)) {
            new RedisDataHealthCheck(client).checkOrThrow();
        }
    }

    static void cache(final RedisDriverSettings settings, final Duration timeout) {
        try (RedisClient client = RedisDriverClientFactory.create(settings, timeout)) {
            new RedisCacheHealthCheck(client).checkOrThrow();
        }
    }
}
