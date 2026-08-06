package group.zn.zero.data.postgresql;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.postgresql.PGProperty;

/**
 * PostgreSQL 数据适配器健康检查。
 *
 * @author zn
 */
public final class PostgresqlDataHealthCheck {

    /**
     * driver 配置。
     */
    private final PostgresqlDriverSettings settings;

    /**
     * 不允许 URL 覆盖原生超时的 JDBC URL。
     */
    private final String boundedJdbcUrl;

    /**
     * 驱动原生整秒超时。
     */
    private final int timeoutSeconds;

    /**
     * JDBC 连接创建工厂。
     */
    private final JdbcConnectionFactory connectionFactory;

    /**
     * 创建 PostgreSQL 数据适配器健康检查。
     *
     * @param settings driver 配置；不可为空。
     * @throws NullPointerException 当配置为空时抛出。
     */
    public PostgresqlDataHealthCheck(final PostgresqlDriverSettings settings) {
        this(settings, Duration.ofSeconds(2));
    }

    /**
     * 使用指定启动预算创建 PostgreSQL 数据适配器健康检查。
     *
     * <p>PostgreSQL JDBC 的 connect/socket timeout 与 JDBC {@code isValid} 均为整秒精度；
     * 本实现向下取整以保证不超过预算，因此不能可靠表达小于一秒的预算。
     *
     * @param settings driver 配置；不可为空。
     * @param timeout 启动健康预算；必须至少为一秒。
     * @throws NullPointerException 当配置或 timeout 为空时抛出。
     * @throws IllegalArgumentException 当 timeout 非正数或小于一秒时抛出。
     */
    public PostgresqlDataHealthCheck(
            final PostgresqlDriverSettings settings,
            final Duration timeout) {
        this(settings, timeout, DriverManager::getConnection);
    }

    /**
     * 使用指定 JDBC 创建工厂构造健康检查。
     *
     * @param settings driver 配置；不可为空。
     * @param timeout 启动健康预算；必须至少为一秒。
     * @param connectionFactory JDBC 连接创建工厂；不可为空。
     */
    PostgresqlDataHealthCheck(
            final PostgresqlDriverSettings settings,
            final Duration timeout,
            final JdbcConnectionFactory connectionFactory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.timeoutSeconds = nativeTimeoutSeconds(timeout);
        this.boundedJdbcUrl = removeTimeoutOverrides(this.settings.jdbcUrl());
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    /**
     * 执行健康检查。
     *
     * @return true 表示 PostgreSQL 可用；线程安全。
     */
    public boolean check() {
        try {
            checkOrThrow();
            return true;
        } catch (ZeroException ex) {
            return false;
        }
    }

    /**
     * 执行健康检查，失败时抛出统一异常。
     *
     * @throws ZeroException PostgreSQL 不可用时抛出，绑定 `DataErrorCode.BACKEND_UNAVAILABLE`。
     */
    public void checkOrThrow() {
        Properties properties = connectionProperties();
        try (Connection connection = connectionFactory.open(boundedJdbcUrl, properties)) {
            if (!connection.isValid(timeoutSeconds)) {
                throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE,
                        "postgresql backend is unavailable", null);
            }
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE,
                    "postgresql backend is unavailable", ex);
        }
    }

    private Properties connectionProperties() {
        Properties properties = new Properties();
        PGProperty.USER.set(properties, settings.username());
        PGProperty.PASSWORD.set(properties, settings.password());
        PGProperty.CONNECT_TIMEOUT.set(properties, timeoutSeconds);
        PGProperty.SOCKET_TIMEOUT.set(properties, timeoutSeconds);
        return properties;
    }

    private int nativeTimeoutSeconds(final Duration timeout) {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (checked.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("PostgreSQL native timeout requires at least one second");
        }
        Duration maximum = Duration.ofSeconds(Integer.MAX_VALUE);
        if (checked.compareTo(maximum) >= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) checked.toSeconds();
    }

    private String removeTimeoutOverrides(final String jdbcUrl) {
        int queryIndex = jdbcUrl.indexOf('?');
        if (queryIndex < 0) {
            return jdbcUrl;
        }
        String base = jdbcUrl.substring(0, queryIndex);
        List<String> retained = new ArrayList<>();
        try {
            for (String parameter : jdbcUrl.substring(queryIndex + 1).split("&", -1)) {
                if (!parameter.isEmpty() && !isTimeoutParameter(parameter)) {
                    retained.add(parameter);
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid PostgreSQL JDBC URL");
        }
        return retained.isEmpty() ? base : base + '?' + String.join("&", retained);
    }

    private boolean isTimeoutParameter(final String parameter) {
        int separator = parameter.indexOf('=');
        String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        return PGProperty.CONNECT_TIMEOUT.getName().equals(name)
                || PGProperty.SOCKET_TIMEOUT.getName().equals(name);
    }
}
