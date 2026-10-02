package group.zn.zero.ownership.postgresql;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipLease;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.ProcessIncarnation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** PostgreSQL 适配器的 SQL 参数和异常辅助方法。 */
final class PostgresqlOwnershipSupport {
    private PostgresqlOwnershipSupport() {
    }

    static void bindResource(final PreparedStatement statement, final OwnershipResource resource, final int offset)
            throws SQLException {
        statement.setString(offset, resource.resourceType());
        statement.setString(offset + 1, resource.resourceId());
        statement.setLong(offset + 2, resource.generation());
    }

    static OwnershipLease lease(final ResultSet resultSet, final OwnershipResource resource) throws SQLException {
        return new OwnershipLease(resource,
                new ProcessIncarnation(resultSet.getString("process_id"), resultSet.getString("incarnation_id")),
                resultSet.getLong("epoch"), resultSet.getTimestamp("expires_at").toInstant());
    }

    static Instant databaseNow(final Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
                ResultSet results = statement.executeQuery()) {
            results.next();
            return results.getTimestamp(1).toInstant();
        }
    }

    static Timestamp timestamp(final Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    static void rollback(final Connection connection, final Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    static OwnershipException failure(final String message, final SQLException failure) {
        return new OwnershipException(
                SystemErrorCode.SYSTEM_ERROR,
                message + ": " + failure.getSQLState(),
                failure);
    }
}
