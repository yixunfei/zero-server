package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Redis driver 连接配置测试。
 *
 * @author zn
 */
class RedisDriverSettingsTest {

    /**
     * 验证配置文本不会暴露 Redis URI 或内嵌凭据。
     */
    @Test
    void toStringShouldNotExposeUri() {
        String uri = "redis://redis-user:redis-password@redis-secret:6379/7";

        String text = new RedisDriverSettings(uri).toString();

        assertFalse(text.contains(uri), text);
        assertFalse(text.contains("redis-user"), text);
        assertFalse(text.contains("redis-password"), text);
        assertFalse(text.contains("redis-secret"), text);
        assertTrue(text.contains("uriConfigured=true"), text);
    }

    /**
     * 验证系统属性优先级高于默认值和环境变量。
     */
    @Test
    void settingsShouldPreferSystemProperty() {
        String previous = System.getProperty(RedisDriverSettings.PROPERTY_REDIS_URI);
        try {
            System.setProperty(RedisDriverSettings.PROPERTY_REDIS_URI, "redis://127.0.0.1:6389/7");

            RedisDriverSettings settings = RedisDriverSettings.fromSystemProperties();

            assertEquals("redis://127.0.0.1:6389/7", settings.uri());
        } finally {
            if (previous == null) {
                System.clearProperty(RedisDriverSettings.PROPERTY_REDIS_URI);
            } else {
                System.setProperty(RedisDriverSettings.PROPERTY_REDIS_URI, previous);
            }
        }
    }
}
