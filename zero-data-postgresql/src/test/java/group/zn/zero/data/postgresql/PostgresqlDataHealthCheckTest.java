package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

/**
 * PostgreSQL 启动健康原生超时测试。
 *
 * @author zn
 */
class PostgresqlDataHealthCheckTest {

    /**
     * 验证连接、socket 与 isValid timeout 都不超过预算，且 URL 无法覆盖限制。
     */
    @Test
    void healthCheckShouldApplyNativeTimeoutWithinBudget() {
        AtomicReference<String> usedUrl = new AtomicReference<>();
        AtomicReference<Properties> usedProperties = new AtomicReference<>();
        AtomicInteger validationTimeoutSeconds = new AtomicInteger();
        PostgresqlDriverSettings settings = new PostgresqlDriverSettings(
                "jdbc:postgresql://postgres.example:5432/zero_game"
                        + "?connectTimeout=99&socketTimeout=88&applicationName=zero-test",
                "postgres-user",
                "postgres-password",
                "zero_data_object");
        JdbcConnectionFactory factory = (url, properties) -> {
            usedUrl.set(url);
            usedProperties.set(properties);
            return connection(validationTimeoutSeconds);
        };
        PostgresqlDataHealthCheck healthCheck = new PostgresqlDataHealthCheck(
                settings,
                Duration.ofMillis(2_500),
                factory);

        healthCheck.checkOrThrow();

        assertEquals("2", PGProperty.CONNECT_TIMEOUT.getOrNull(usedProperties.get()));
        assertEquals("2", PGProperty.SOCKET_TIMEOUT.getOrNull(usedProperties.get()));
        assertEquals("postgres-user", PGProperty.USER.getOrNull(usedProperties.get()));
        assertEquals("postgres-password", PGProperty.PASSWORD.getOrNull(usedProperties.get()));
        assertEquals(2, validationTimeoutSeconds.get());
        assertFalse(usedUrl.get().contains("connectTimeout"));
        assertFalse(usedUrl.get().contains("socketTimeout"));
        assertTrue(usedUrl.get().contains("applicationName=zero-test"));
    }

    /**
     * 验证 JDBC 整秒原生 timeout 无法表达的预算会被明确拒绝。
     */
    @Test
    void healthCheckShouldRejectUnsupportedTimeout() {
        PostgresqlDriverSettings settings = new PostgresqlDriverSettings(
                "jdbc:postgresql://127.0.0.1:5432/zero_game",
                "postgres-user",
                "postgres-password",
                "zero_data_object");

        assertThrows(NullPointerException.class, () -> new PostgresqlDataHealthCheck(settings, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresqlDataHealthCheck(settings, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresqlDataHealthCheck(settings, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresqlDataHealthCheck(settings, Duration.ofMillis(999)));
    }

    private static Connection connection(final AtomicInteger validationTimeoutSeconds) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isValid" -> {
                        validationTimeoutSeconds.set((Integer) arguments[0]);
                        yield true;
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
