package group.zn.zero.data.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Objects;
import org.postgresql.ds.PGSimpleDataSource;

/** PostgreSQL 连接池装配；跨仓库共享有界物理连接，生命周期由装配方管理。 */
public final class PostgresqlPool {
    private PostgresqlPool() { }

    /**
     * 创建惰性连接池；线程安全，不立即连接服务器；返回资源必须关闭。
     *
     * @param settings 驱动连接配置。
     * @param maximumConnections 物理连接上限；必须为正。
     * @param acquisitionTimeout 等待连接上限；至少 250ms。
     * @return 调用方拥有的池；非空。
     * @throws IllegalArgumentException 池容量或超时非法时抛出。
     */
    public static HikariDataSource create(final PostgresqlDriverSettings settings, final int maximumConnections,
                                         final Duration acquisitionTimeout) {
        Objects.requireNonNull(settings, "settings");
        long timeoutMillis = Objects.requireNonNull(acquisitionTimeout, "acquisitionTimeout").toMillis();
        if (maximumConnections < 1 || timeoutMillis < 250L) {
            throw new IllegalArgumentException("pool requires positive capacity and timeout >= 250ms");
        }
        var driver = new PGSimpleDataSource();
        driver.setURL(settings.jdbcUrl());
        driver.setUser(settings.username());
        driver.setPassword(settings.password());
        var pool = new HikariDataSource();
        pool.setDataSource(driver);
        pool.setMaximumPoolSize(maximumConnections);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(timeoutMillis);
        pool.setValidationTimeout(Math.min(timeoutMillis, 5_000L));
        pool.setInitializationFailTimeout(-1L);
        return pool;
    }
}
