package group.zn.zero.data.mongo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MongoDB driver 连接配置测试。
 *
 * @author zn
 */
class MongoDriverSettingsTest {

    /**
     * 验证配置文本不会暴露连接串或数据库名称原值。
     */
    @Test
    void toStringShouldNotExposeConnectionValues() {
        String connectionString = "mongodb://mongo-user:mongo-password@mongo-secret:27017";
        String databaseName = "mongo-database-secret";

        String text = new MongoDriverSettings(connectionString, databaseName).toString();

        assertFalse(text.contains(connectionString), text);
        assertFalse(text.contains("mongo-user"), text);
        assertFalse(text.contains("mongo-password"), text);
        assertFalse(text.contains("mongo-secret"), text);
        assertFalse(text.contains(databaseName), text);
        assertTrue(text.contains("connectionStringConfigured=true"), text);
        assertTrue(text.contains("databaseNameConfigured=true"), text);
    }
}
