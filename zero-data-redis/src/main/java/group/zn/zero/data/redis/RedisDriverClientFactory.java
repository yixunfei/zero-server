package group.zn.zero.data.redis;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.util.JedisURIHelper;

/**
 * Redis driver client 创建工厂。
 *
 * <p>工厂从标准 Redis URI 保留 endpoint、身份、库号、协议与 SSL 语义，并显式覆盖连接和读超时。
 *
 * @author zn
 */
public final class RedisDriverClientFactory {

    private RedisDriverClientFactory() {
    }

    /**
     * 使用统一原生超时创建 Redis client。
     *
     * <p>Jedis 仅支持毫秒精度，小于一毫秒的正数会按一毫秒处理。
     *
     * @param settings Redis driver 连接配置；不可为空。
     * @param timeout 连接、socket 与阻塞 socket 超时；必须为正数。
     * @return Redis client；不可为空；调用方负责关闭；线程安全性由 Jedis 声明。
     * @throws NullPointerException 当配置或超时为空时抛出。
     * @throws IllegalArgumentException 当 URI 非法或超时非正数时抛出；异常不会回显 URI。
     */
    public static RedisClient create(final RedisDriverSettings settings, final Duration timeout) {
        RedisDriverSettings checkedSettings = Objects.requireNonNull(settings, "settings");
        URI uri = parseUri(checkedSettings);
        int timeoutMillis = nativeTimeoutMillis(timeout);
        try {
            return RedisClient.builder()
                    .hostAndPort(JedisURIHelper.getHostAndPort(uri))
                    .clientConfig(clientConfig(uri, timeoutMillis))
                    .build();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("create Redis client failed");
        }
    }

    /**
     * 构造保留 URI 语义且应用原生超时的 Jedis client config。
     *
     * @param settings Redis driver 连接配置；不可为空。
     * @param timeout 原生超时；必须为正数。
     * @return Jedis client config；不可为空；不可变；线程安全。
     */
    static DefaultJedisClientConfig clientConfig(
            final RedisDriverSettings settings,
            final Duration timeout) {
        return clientConfig(parseUri(Objects.requireNonNull(settings, "settings")), nativeTimeoutMillis(timeout));
    }

    /**
     * 解析 Redis endpoint。
     *
     * @param settings Redis driver 连接配置；不可为空。
     * @return Redis endpoint；不可为空；不可变；线程安全。
     */
    static HostAndPort hostAndPort(final RedisDriverSettings settings) {
        try {
            return JedisURIHelper.getHostAndPort(parseUri(Objects.requireNonNull(settings, "settings")));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid Redis URI");
        }
    }

    private static DefaultJedisClientConfig clientConfig(final URI uri, final int timeoutMillis) {
        return DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeoutMillis)
                .socketTimeoutMillis(timeoutMillis)
                .blockingSocketTimeoutMillis(timeoutMillis)
                .user(JedisURIHelper.getUser(uri))
                .password(JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri))
                .protocol(JedisURIHelper.getRedisProtocol(uri))
                .ssl(JedisURIHelper.isRedisSSLScheme(uri))
                .build();
    }

    private static URI parseUri(final RedisDriverSettings settings) {
        try {
            URI uri = URI.create(settings.uri());
            if (!JedisURIHelper.isValid(uri)) {
                throw new IllegalArgumentException("invalid Redis URI");
            }
            return uri;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid Redis URI");
        }
    }

    private static int nativeTimeoutMillis(final Duration timeout) {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Duration maximum = Duration.ofMillis(Integer.MAX_VALUE);
        if (checked.compareTo(maximum) >= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(1L, checked.toMillis());
    }
}
