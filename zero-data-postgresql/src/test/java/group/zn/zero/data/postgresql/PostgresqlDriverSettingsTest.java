package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PostgreSQL driver 连接配置测试。
 *
 * @author zn
 */
class PostgresqlDriverSettingsTest {

    /**
     * 验证配置文本不会暴露 JDBC URL、账号、密码或表名原值。
     */
    @Test
    void toStringShouldNotExposeConnectionValues() {
        String jdbcUrl = "jdbc:postgresql://postgres-secret:5432/database-secret";
        String username = "postgres-user-secret";
        String password = "postgres-password-secret";
        String tableName = "postgres_table_secret";

        String text = new PostgresqlDriverSettings(jdbcUrl, username, password, tableName).toString();

        assertFalse(text.contains(jdbcUrl), text);
        assertFalse(text.contains("postgres-secret"), text);
        assertFalse(text.contains(username), text);
        assertFalse(text.contains(password), text);
        assertFalse(text.contains(tableName), text);
        assertTrue(text.contains("jdbcUrlConfigured=true"), text);
        assertTrue(text.contains("usernameConfigured=true"), text);
        assertTrue(text.contains("passwordConfigured=true"), text);
        assertTrue(text.contains("tableNameConfigured=true"), text);
    }
}
