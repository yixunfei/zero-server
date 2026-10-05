package group.zn.zero.ownership.postgresql;

import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipLease;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.OwnershipStore;
import group.zn.zero.ownership.ProcessIncarnation;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 使用 PostgreSQL 行锁和数据库时间实现跨进程 lease/epoch fencing。 */
public final class PostgresqlOwnershipStore implements OwnershipStore {
    private final PostgresqlOwnershipDatabase database;

    /** 创建 ownership store，并初始化其独立表前缀。 */
    public PostgresqlOwnershipStore(final PostgresqlOwnershipDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** 获取未占用或已过期资源的新 epoch。 */
    @Override
    public OwnershipLease acquire(final OwnershipResource resource, final ProcessIncarnation owner,
            final Duration leaseDuration) {
        return issue(resource, owner, leaseDuration, false);
    }

    /** 续约当前 owner；过期或旧 epoch 会被拒绝。 */
    @Override
    public OwnershipLease renew(final OwnershipLease lease, final Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease");
        requireDuration(leaseDuration);
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                String sql = "update " + database.ownershipTable() + " set expires_at="
                        + "date_trunc('microseconds', clock_timestamp() + (? * interval '1 second')) where "
                        + "resource_type=? and resource_id=? and generation=? and process_id=? and incarnation_id=? "
                        + "and epoch=? and expires_at>=clock_timestamp() returning resource_type, resource_id, generation, process_id, "
                        + "incarnation_id, epoch, expires_at";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    BigDecimal seconds = BigDecimal.valueOf(leaseDuration.getSeconds())
                            .add(BigDecimal.valueOf(leaseDuration.getNano(), 9));
                    statement.setBigDecimal(1, seconds);
                    PostgresqlOwnershipSupport.bindResource(statement, lease.resource(), 2);
                    statement.setString(5, lease.owner().processId());
                    statement.setString(6, lease.owner().incarnationId());
                    statement.setLong(7, lease.epoch());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new OwnershipException("stale or expired ownership lease");
                        }
                        OwnershipLease renewed = PostgresqlOwnershipSupport.lease(result, lease.resource());
                        connection.commit();
                        return renewed;
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                PostgresqlOwnershipSupport.rollback(connection, failure);
                if (failure instanceof SQLException sqlFailure) {
                    throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership renew failed", sqlFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership connection failed", failure);
        }
    }

    /** 仅释放仍有效且身份匹配的 lease。 */
    @Override
    public boolean release(final OwnershipLease lease) {
        Objects.requireNonNull(lease, "lease");
        try (Connection connection = database.connection()) {
            // 保留历史 epoch；删除行会让后续 acquire 从 1 重新开始，使旧租约身份复活。
            String sql = "update " + database.ownershipTable()
                    + " set expires_at=clock_timestamp() - interval '1 microsecond' where resource_type=? and resource_id=? "
                    + "and generation=? and process_id=? and incarnation_id=? and epoch=? and expires_at>=clock_timestamp()";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                PostgresqlOwnershipSupport.bindResource(statement, lease.resource(), 1);
                statement.setString(4, lease.owner().processId());
                statement.setString(5, lease.owner().incarnationId());
                statement.setLong(6, lease.epoch());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership release failed", failure);
        }
    }

    /** 在资源过期后接管；有效旧 owner 存在时拒绝。 */
    @Override
    public OwnershipLease claim(final OwnershipResource resource, final ProcessIncarnation owner,
            final Duration leaseDuration) {
        return issue(resource, owner, leaseDuration, true);
    }

    /** 查询数据库时间下仍有效的当前 lease。 */
    @Override
    public Optional<OwnershipLease> current(final OwnershipResource resource, final Instant ignoredNow) {
        Objects.requireNonNull(resource, "resource");
        try (Connection connection = database.connection()) {
            String sql = "select process_id, incarnation_id, epoch, expires_at from " + database.ownershipTable()
                    + " where resource_type=? and resource_id=? and generation=? and expires_at>=clock_timestamp()";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(PostgresqlOwnershipSupport.lease(result, resource)) : Optional.empty();
                }
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership read failed", failure);
        }
    }

    /** 使用数据库时钟和行锁确认 lease，供迁移激活等权威流程调用。 */
    @Override
    public void assertCurrent(final OwnershipLease lease) {
        Objects.requireNonNull(lease, "lease");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                String sql = "select process_id, incarnation_id, epoch, expires_at from " + database.ownershipTable()
                        + " where resource_type=? and resource_id=? and generation=? for update";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    PostgresqlOwnershipSupport.bindResource(statement, lease.resource(), 1);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new OwnershipException("resource is not currently owned");
                        }
                        OwnershipLease current = PostgresqlOwnershipSupport.lease(result, lease.resource());
                        if (!sameLease(current, lease)
                                || !current.validAt(PostgresqlOwnershipSupport.databaseNow(connection))) {
                            throw new OwnershipException("stale owner epoch or process incarnation");
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                PostgresqlOwnershipSupport.rollback(connection, failure);
                throw failure;
            }
        } catch (OwnershipException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership assertion failed", failure);
        }
    }

    private OwnershipLease issue(final OwnershipResource resource, final ProcessIncarnation owner,
            final Duration leaseDuration, final boolean claim) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(owner, "owner");
        requireDuration(leaseDuration);
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                String select = "select process_id, incarnation_id, epoch, expires_at from " + database.ownershipTable()
                        + " where resource_type=? and resource_id=? and generation=? for update";
                OwnershipLease existing = null;
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            existing = PostgresqlOwnershipSupport.lease(result, resource);
                        }
                    }
                }
                Instant now = PostgresqlOwnershipSupport.databaseNow(connection);
                if (existing != null && !existing.expiresAt().isBefore(now)) {
                    throw new OwnershipException(claim ? "resource has not expired" : "resource is already owned");
                }
                long epoch = existing == null ? 1L : Math.addExact(existing.epoch(), 1L);
                Instant expiresAt = PostgresqlOwnershipSupport.timestamp(now.plus(leaseDuration)).toInstant();
                String sql = existing == null
                        ? "insert into " + database.ownershipTable() + " (resource_type, resource_id, generation, "
                        + "process_id, incarnation_id, epoch, expires_at) values (?, ?, ?, ?, ?, ?, ?)"
                        : "update " + database.ownershipTable() + " set process_id=?, incarnation_id=?, epoch=?, expires_at=? "
                        + "where resource_type=? and resource_id=? and generation=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    if (existing == null) {
                        PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
                        statement.setString(4, owner.processId());
                        statement.setString(5, owner.incarnationId());
                        statement.setLong(6, epoch);
                        statement.setTimestamp(7, Timestamp.from(expiresAt));
                    } else {
                        statement.setString(1, owner.processId());
                        statement.setString(2, owner.incarnationId());
                        statement.setLong(3, epoch);
                        statement.setTimestamp(4, Timestamp.from(expiresAt));
                        PostgresqlOwnershipSupport.bindResource(statement, resource, 5);
                    }
                    statement.executeUpdate();
                }
                connection.commit();
                return new OwnershipLease(resource, owner, epoch, expiresAt);
            } catch (SQLException | RuntimeException failure) {
                PostgresqlOwnershipSupport.rollback(connection, failure);
                throw failure;
            }
        } catch (OwnershipException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL ownership acquire failed", failure);
        }
    }

    private static void requireDuration(final Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static boolean sameLease(final OwnershipLease left, final OwnershipLease right) {
        return left.resource().equals(right.resource()) && left.owner().equals(right.owner())
                && left.epoch() == right.epoch();
    }
}
