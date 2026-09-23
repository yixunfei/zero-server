package group.zn.zero.data.postgresql;

import java.util.Objects;

/**
 * PostgreSQL driver 连接配置。
 *
 * @param jdbcUrl JDBC URL。
 * @param username 用户名。
 * @param password 密码。
 * @param tableName 通用对象表名称。
 * @author zn
 */
public record PostgresqlDriverSettings(String jdbcUrl, String username, String password, String tableName) {

    /**
     * JDBC URL 系统属性名。
     */
    public static final String JDBC_URL_PROPERTY = "zero.postgresql.url";

    /**
     * 用户名系统属性名。
     */
    public static final String USERNAME_PROPERTY = "zero.postgresql.username";

    /**
     * 密码系统属性名。
     */
    public static final String PASSWORD_PROPERTY = "zero.postgresql.password";

    /**
     * 通用对象表系统属性名。
     */
    public static final String TABLE_NAME_PROPERTY = "zero.postgresql.table";

    /**
     * JDBC URL 环境变量名。
     */
    public static final String JDBC_URL_ENV = "ZERO_POSTGRESQL_URL";

    /**
     * 用户名环境变量名。
     */
    public static final String USERNAME_ENV = "ZERO_POSTGRES_USER";

    /**
     * 密码环境变量名。
     */
    public static final String PASSWORD_ENV = "ZERO_POSTGRES_PASSWORD";

    /**
     * 通用对象表环境变量名。
     */
    public static final String TABLE_NAME_ENV = "ZERO_POSTGRESQL_TABLE";

    /**
     * 默认通用对象表名称。
     */
    public static final String DEFAULT_TABLE_NAME = "zero_data_object";

    /**
     * 创建 PostgreSQL driver 连接配置。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当必要字段为空白或表名非法时抛出。
     */
    public PostgresqlDriverSettings {
        jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        username = requireText(username, "username");
        password = Objects.requireNonNull(password, "password");
        tableName = requireIdentifier(tableName);
    }

    /**
     * 从系统属性读取 PostgreSQL 配置。
     *
     * @return PostgreSQL driver 连接配置；不可为空；线程安全。
     * @throws IllegalArgumentException 当 JDBC URL、用户名或密码未通过系统属性或环境变量提供时抛出。
     */
    public static PostgresqlDriverSettings fromSystemProperties() {
        return new PostgresqlDriverSettings(
                requiredSetting(JDBC_URL_PROPERTY, JDBC_URL_ENV),
                requiredSetting(USERNAME_PROPERTY, USERNAME_ENV),
                requiredSetting(PASSWORD_PROPERTY, PASSWORD_ENV),
                optionalSetting(TABLE_NAME_PROPERTY, TABLE_NAME_ENV, DEFAULT_TABLE_NAME));
    }

    /**
     * 判断系统属性或环境变量是否提供了必需连接配置。
     *
     * @return true 表示配置完整；线程安全。
     */
    public static boolean hasRequiredSettings() {
        return hasText(value(JDBC_URL_PROPERTY, JDBC_URL_ENV))
                && hasText(value(USERNAME_PROPERTY, USERNAME_ENV))
                && hasText(value(PASSWORD_PROPERTY, PASSWORD_ENV));
    }

    /**
     * 返回不包含 JDBC URL、账号、密码和表名原值的安全文本。
     *
     * @return 安全配置摘要；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "PostgresqlDriverSettings{"
                + "jdbcUrlConfigured=" + hasText(jdbcUrl)
                + ", usernameConfigured=" + hasText(username)
                + ", passwordConfigured=" + hasText(password)
                + ", tableNameConfigured=" + hasText(tableName)
                + '}';
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    static String requireIdentifier(final String value) {
        String current = requireText(value, "tableName");
        if (!current.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("tableName must be a safe SQL identifier");
        }
        return current;
    }

    private static String requiredSetting(final String propertyName, final String environmentName) {
        String current = value(propertyName, environmentName);
        if (!hasText(current)) {
            throw new IllegalArgumentException(
                    "missing PostgreSQL setting: " + propertyName + " or " + environmentName);
        }
        return current;
    }

    private static String optionalSetting(
            final String propertyName,
            final String environmentName,
            final String defaultValue) {
        String current = value(propertyName, environmentName);
        return hasText(current) ? current : defaultValue;
    }

    private static String value(final String propertyName, final String environmentName) {
        String propertyValue = System.getProperty(propertyName);
        return hasText(propertyValue) ? propertyValue : System.getenv(environmentName);
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
