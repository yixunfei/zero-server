package group.zn.zero.data.redis;

import java.util.Objects;

/**
 * Redis driver 连接配置。
 *
 * @param uri Redis 连接 URI。
 * @author zn
 */
public record RedisDriverSettings(String uri) {

    /**
     * 默认 Redis URI。
     */
    public static final String DEFAULT_URI = "redis://127.0.0.1:6379/0";

    /**
     * Redis URI 环境变量名称。
     */
    public static final String ENV_REDIS_URI = "ZERO_REDIS_URI";

    /**
     * Redis URI 系统属性名称。
     */
    public static final String PROPERTY_REDIS_URI = "zero.redis.uri";

    /**
     * 创建 Redis driver 连接配置。
     *
     * @throws NullPointerException 当 URI 为空时抛出。
     * @throws IllegalArgumentException 当 URI 为空白时抛出。
     */
    public RedisDriverSettings {
        uri = Objects.requireNonNull(uri, "uri");
        if (uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
    }

    /**
     * 从系统属性读取 Redis 配置。
     *
     * @return Redis driver 连接配置；不可为空；线程安全。
     */
    public static RedisDriverSettings fromSystemProperties() {
        String propertyUri = System.getProperty(PROPERTY_REDIS_URI);
        if (hasText(propertyUri)) {
            return new RedisDriverSettings(propertyUri);
        }
        String environmentUri = System.getenv(ENV_REDIS_URI);
        if (hasText(environmentUri)) {
            return new RedisDriverSettings(environmentUri);
        }
        return new RedisDriverSettings(DEFAULT_URI);
    }

    /**
     * 返回不包含 Redis URI 原值的安全文本。
     *
     * @return 安全配置摘要；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "RedisDriverSettings{uriConfigured=" + hasText(uri) + '}';
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
