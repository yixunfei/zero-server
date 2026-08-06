package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;

/**
 * Redis driver client 工厂测试。
 *
 * @author zn
 */
class RedisDriverClientFactoryTest {

    /**
     * 验证原生超时配置会保留 URI 中的 endpoint、身份、库号和 SSL 语义。
     */
    @Test
    void clientConfigShouldApplyTimeoutAndPreserveUriSemantics() {
        RedisDriverSettings settings = new RedisDriverSettings(
                "rediss://redis-user:redis-password@redis.example:6380/7");

        DefaultJedisClientConfig clientConfig = RedisDriverClientFactory.clientConfig(
                settings,
                Duration.ofMillis(1_250));
        HostAndPort endpoint = RedisDriverClientFactory.hostAndPort(settings);

        assertEquals(1_250, clientConfig.getConnectionTimeoutMillis());
        assertEquals(1_250, clientConfig.getSocketTimeoutMillis());
        assertEquals(1_250, clientConfig.getBlockingSocketTimeoutMillis());
        assertEquals("redis-user", clientConfig.getUser());
        assertEquals("redis-password", clientConfig.getPassword());
        assertEquals(7, clientConfig.getDatabase());
        assertTrue(clientConfig.isSsl());
        assertEquals("redis.example", endpoint.getHost());
        assertEquals(6380, endpoint.getPort());
    }

    /**
     * 验证非 SSL URI 保持非 SSL，且 timeout 必须为正数。
     */
    @Test
    void clientConfigShouldRejectNonPositiveTimeout() {
        RedisDriverSettings settings = new RedisDriverSettings("redis://127.0.0.1:6379/0");

        assertFalse(RedisDriverClientFactory.clientConfig(settings, Duration.ofMillis(1)).isSsl());
        assertThrows(NullPointerException.class,
                () -> RedisDriverClientFactory.clientConfig(settings, null));
        assertThrows(IllegalArgumentException.class,
                () -> RedisDriverClientFactory.clientConfig(settings, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> RedisDriverClientFactory.clientConfig(settings, Duration.ofMillis(-1)));
    }

    /**
     * 验证非法 URI 诊断不会回显 URI 原值。
     */
    @Test
    void invalidUriShouldNotBeEchoed() {
        String invalidUri = "redis://redis-password-secret@bad host:6379/0";
        RedisDriverSettings settings = new RedisDriverSettings(invalidUri);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RedisDriverClientFactory.clientConfig(settings, Duration.ofSeconds(1)));

        assertFalse(exception.getMessage().contains(invalidUri));
        assertFalse(exception.getMessage().contains("redis-password-secret"));
    }
}
