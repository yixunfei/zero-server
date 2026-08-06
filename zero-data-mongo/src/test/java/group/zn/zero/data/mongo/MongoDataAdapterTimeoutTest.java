package group.zn.zero.data.mongo;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * MongoDB driver 原生超时配置测试。
 *
 * @author zn
 */
class MongoDataAdapterTimeoutTest {

    /**
     * 验证生产超时会覆盖 URI 内较宽松的超时，同时保留认证与 TLS 配置。
     */
    @Test
    void clientSettingsShouldApplyNativeTimeoutAndPreserveConnectionStringOptions() {
        MongoDriverSettings settings = new MongoDriverSettings(
                "mongodb://mongo-user:mongo-password@mongo.example:27018/zero_game"
                        + "?tls=true&connectTimeoutMS=9000&socketTimeoutMS=9000&serverSelectionTimeoutMS=9000",
                "zero_game");

        MongoClientSettings clientSettings = new MongoDataAdapter()
                .createClientSettings(settings, Duration.ofMillis(1_250));

        assertEquals(1_250, clientSettings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS));
        assertEquals(1_250, clientSettings.getSocketSettings().getConnectTimeout(MILLISECONDS));
        assertEquals(1_250, clientSettings.getSocketSettings().getReadTimeout(MILLISECONDS));
        assertEquals("mongo-user", clientSettings.getCredential().getUserName());
        assertArrayEquals("mongo-password".toCharArray(), clientSettings.getCredential().getPassword());
        assertEquals("mongo.example", clientSettings.getClusterSettings().getHosts().getFirst().getHost());
        assertTrue(clientSettings.getSslSettings().isEnabled());
    }

    /**
     * 验证 MongoDB timeout 必须为正数。
     */
    @Test
    void clientSettingsShouldRejectNonPositiveTimeout() {
        MongoDataAdapter adapter = new MongoDataAdapter();
        MongoDriverSettings settings = MongoDriverSettings.fromSystemProperties();

        assertThrows(NullPointerException.class, () -> adapter.createClientSettings(settings, null));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.createClientSettings(settings, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.createClientSettings(settings, Duration.ofMillis(-1)));
    }

    /**
     * 验证非法连接串诊断不会回显连接串或凭据。
     */
    @Test
    void invalidConnectionStringShouldNotBeEchoed() {
        String invalidConnectionString = "mongodb://mongo-user:mongo-password-secret@[bad-host";
        MongoDriverSettings settings = new MongoDriverSettings(invalidConnectionString, "zero_game");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MongoDataAdapter().createClientSettings(settings, Duration.ofSeconds(1)));

        assertFalse(exception.getMessage().contains(invalidConnectionString));
        assertFalse(exception.getMessage().contains("mongo-password-secret"));
    }
}
