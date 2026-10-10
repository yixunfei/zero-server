package group.zn.zero.ownership.postgresql;

import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipLease;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.OwnershipRoute;
import group.zn.zero.ownership.OwnershipRouter;
import group.zn.zero.ownership.ProcessIncarnation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 使用 PostgreSQL 权威 lease 校验 owner 路由的可选实现。 */
public final class PostgresqlOwnershipRouter implements OwnershipRouter {
    private final PostgresqlOwnershipDatabase database;

    /** 创建路由适配器。 */
    public PostgresqlOwnershipRouter(final PostgresqlOwnershipDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<OwnershipRoute> resolve(final OwnershipResource resource) {
        Objects.requireNonNull(resource, "resource");
        String sql = "select r.process_id, r.incarnation_id, r.epoch, r.endpoint, r.expires_at from "
                + database.routeTable() + " r join " + database.ownershipTable() + " l on "
                + "r.resource_type=l.resource_type and r.resource_id=l.resource_id and r.generation=l.generation "
                + "and r.process_id=l.process_id and r.incarnation_id=l.incarnation_id and r.epoch=l.epoch "
                + "where r.resource_type=? and r.resource_id=? and r.generation=? "
                + "and r.expires_at>=clock_timestamp() and l.expires_at>=clock_timestamp()";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRoute(result, resource)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership route read failed", failure);
        }
    }

    @Override
    public OwnershipRoute publish(final OwnershipLease lease, final String endpoint) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        try (Connection connection = database.connection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                validateLease(connection, lease);
                String sql = "insert into " + database.routeTable() + " (resource_type, resource_id, generation, "
                        + "process_id, incarnation_id, epoch, endpoint, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (resource_type, resource_id, generation) do update set process_id=?, "
                        + "incarnation_id=?, epoch=?, endpoint=?, expires_at=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    PostgresqlOwnershipSupport.bindResource(statement, lease.resource(), 1);
                    statement.setString(4, lease.owner().processId());
                    statement.setString(5, lease.owner().incarnationId());
                    statement.setLong(6, lease.epoch());
                    statement.setString(7, endpoint);
                    statement.setTimestamp(8, PostgresqlOwnershipSupport.timestamp(lease.expiresAt()));
                    statement.setString(9, lease.owner().processId());
                    statement.setString(10, lease.owner().incarnationId());
                    statement.setLong(11, lease.epoch());
                    statement.setString(12, endpoint);
                    statement.setTimestamp(13, PostgresqlOwnershipSupport.timestamp(lease.expiresAt()));
                    statement.executeUpdate();
                }
                connection.commit();
                return new OwnershipRoute(lease.resource(), lease.owner(), lease.epoch(), endpoint, lease.expiresAt());
            } catch (SQLException | RuntimeException failure) {
                PostgresqlOwnershipSupport.rollback(connection, failure);
                throw failure;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException failure) {
                    throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership route cleanup failed", failure);
                }
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership route publish failed", failure);
        }
    }

    @Override
    public boolean withdraw(final OwnershipLease lease) {
        Objects.requireNonNull(lease, "lease");
        String sql = "delete from " + database.routeTable() + " where resource_type=? and resource_id=? "
                + "and generation=? and process_id=? and incarnation_id=? and epoch=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, lease.resource(), 1);
            statement.setString(4, lease.owner().processId());
            statement.setString(5, lease.owner().incarnationId());
            statement.setLong(6, lease.epoch());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership route withdraw failed", failure);
        }
    }

    private void validateLease(final Connection connection, final OwnershipLease lease) throws SQLException {
        String sql = "select process_id, incarnation_id, epoch, expires_at from " + database.ownershipTable()
                + " where resource_type=? and resource_id=? and generation=? for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, lease.resource(), 1);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new OwnershipException("resource has no ownership lease");
                }
                OwnershipLease current = PostgresqlOwnershipSupport.lease(result, lease.resource());
                Instant now = PostgresqlOwnershipSupport.databaseNow(connection);
                if (!sameLease(current, lease) || !current.validAt(now)) {
                    throw new OwnershipException("stale or expired ownership lease");
                }
            }
        }
    }

    private static OwnershipRoute readRoute(final ResultSet result, final OwnershipResource resource)
            throws SQLException {
        return new OwnershipRoute(resource,
                new ProcessIncarnation(result.getString("process_id"), result.getString("incarnation_id")),
                result.getLong("epoch"), result.getString("endpoint"), result.getTimestamp("expires_at").toInstant());
    }

    private static boolean sameLease(final OwnershipLease left, final OwnershipLease right) {
        return left.owner().equals(right.owner()) && left.epoch() == right.epoch()
                && left.resource().equals(right.resource());
    }
}
