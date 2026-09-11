package group.zn.zero.runtime.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;

/** Runtime-owned bounded pool. Driver stores depend only on the standard DataSource contract. */
final class PostgresqlConnectionPool {
    private PostgresqlConnectionPool() { }

    static HikariDataSource create(final PostgresqlDriverSettings settings, final int maximumConnections,
                                  final Duration acquisitionTimeout) {
        var driver = new PGSimpleDataSource();
        driver.setURL(settings.jdbcUrl());
        driver.setUser(settings.username());
        driver.setPassword(settings.password());
        var pool = new HikariDataSource();
        pool.setDataSource(driver);
        pool.setMaximumPoolSize(maximumConnections);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(acquisitionTimeout.toMillis());
        pool.setValidationTimeout(Math.min(acquisitionTimeout.toMillis(), 5_000));
        pool.setInitializationFailTimeout(-1);
        return pool;
    }
}
