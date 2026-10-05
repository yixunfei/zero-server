package group.zn.zero.runtime.postgresql;

import com.zaxxer.hikari.HikariDataSource;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import java.time.Duration;
import group.zn.zero.data.postgresql.PostgresqlPool;

/** Runtime-owned bounded pool. Driver stores depend only on the standard DataSource contract. */
final class PostgresqlConnectionPool {
    private PostgresqlConnectionPool() { }

    static HikariDataSource create(final PostgresqlDriverSettings settings, final int maximumConnections,
                                  final Duration acquisitionTimeout) {
        return PostgresqlPool.create(settings, maximumConnections, acquisitionTimeout);
    }
}
