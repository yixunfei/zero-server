package group.zn.zero.data.mongo;

import java.util.Objects;

/**
 * MongoDB driver 连接配置。
 *
 * @param connectionString MongoDB 连接串。
 * @param databaseName 数据库名称。
 * @author zn
 */
public record MongoDriverSettings(String connectionString, String databaseName) {

    /**
     * 默认连接串。
     */
    public static final String DEFAULT_CONNECTION_STRING = "mongodb://127.0.0.1:27017";

    /**
     * 默认数据库名称。
     */
    public static final String DEFAULT_DATABASE_NAME = "zero_test";

    /**
     * MongoDB 连接串系统属性名。
     */
    public static final String PROPERTY_MONGO_URI = "zero.mongo.uri";

    /**
     * MongoDB 数据库名称系统属性名。
     */
    public static final String PROPERTY_MONGO_DATABASE = "zero.mongo.database";

    /**
     * MongoDB 连接串环境变量名。
     */
    public static final String ENV_MONGO_URI = "ZERO_MONGO_URI";

    /**
     * MongoDB 数据库名称环境变量名。
     */
    public static final String ENV_MONGO_DATABASE = "ZERO_MONGO_DATABASE";

    /**
     * 创建 MongoDB driver 连接配置。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当必要字段为空白时抛出。
     */
    public MongoDriverSettings {
        connectionString = requireText(connectionString, "connectionString");
        databaseName = requireText(databaseName, "databaseName");
    }

    /**
     * 从系统属性读取 MongoDB 配置。
     *
     * @return MongoDB driver 连接配置；不可为空；线程安全。
     */
    public static MongoDriverSettings fromSystemProperties() {
        return new MongoDriverSettings(
                optionalSetting(PROPERTY_MONGO_URI, ENV_MONGO_URI, DEFAULT_CONNECTION_STRING),
                optionalSetting(PROPERTY_MONGO_DATABASE, ENV_MONGO_DATABASE, DEFAULT_DATABASE_NAME));
    }

    /**
     * 返回不包含连接串和数据库名称原值的安全文本。
     *
     * @return 安全配置摘要；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "MongoDriverSettings{"
                + "connectionStringConfigured=" + hasText(connectionString)
                + ", databaseNameConfigured=" + hasText(databaseName)
                + '}';
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private static String optionalSetting(
            final String propertyName,
            final String environmentName,
            final String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (hasText(propertyValue)) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return hasText(environmentValue) ? environmentValue : defaultValue;
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
