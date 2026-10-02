package group.zn.zero.ownership.postgresql;

import group.zn.zero.ownership.CommitResult;
import group.zn.zero.ownership.FencedStateStore;
import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipLease;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.StateCommitRequest;
import group.zn.zero.ownership.StateSnapshot;
import group.zn.zero.ownership.StateVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** 在同一 PostgreSQL 事务内执行 lease fencing、状态 CAS 和 requestId 去重。 */
public final class PostgresqlFencedStateStore<T> implements FencedStateStore<T> {
    private final PostgresqlOwnershipDatabase database;
    private final OwnershipPayloadCodec<T> codec;

    /** 创建状态适配器；codec 由业务选择并负责版本兼容。 */
    public PostgresqlFencedStateStore(final PostgresqlOwnershipDatabase database,
            final OwnershipPayloadCodec<T> codec) {
        this.database = Objects.requireNonNull(database, "database");
        this.codec = OwnershipPayloadCodec.requireNonNullPayload(codec);
    }

    /** 读取当前状态；不存在时返回空。 */
    @Override
    public Optional<StateSnapshot<T>> read(final OwnershipResource resource) {
        Objects.requireNonNull(resource, "resource");
        String sql = "select version, payload from " + database.stateTable()
                + " where resource_type=? and resource_id=? and generation=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StateSnapshot<>(new StateVersion(result.getLong("version")),
                        codec.decode(result.getBytes("payload"))));
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL state read failed", failure);
        }
    }

    /** 原子校验 lease、CAS 版本、写状态并保存请求结果。 */
    @Override
    public CommitResult<T> commit(final StateCommitRequest<T> request) {
        Objects.requireNonNull(request, "request");
        byte[] encoded = codec.encode(request.payload());
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                CommitResult<T> replay = replayIfPresent(connection, request, encoded);
                if (replay != null) {
                    connection.commit();
                    return replay;
                }
                validateLease(connection, request.lease());
                StateVersion actual = currentVersion(connection, request.resource());
                if (!actual.equals(request.expectedVersion())) {
                    throw new OwnershipException("state version conflict");
                }
                StateVersion next = actual.next();
                writeState(connection, request.resource(), next, encoded);
                insertRequest(connection, request, encoded, next);
                connection.commit();
                return new CommitResult<>(true, false, next, request.payload());
            } catch (SQLException | RuntimeException failure) {
                PostgresqlOwnershipSupport.rollback(connection, failure);
                if (failure instanceof SQLException sqlFailure) {
                    throw PostgresqlOwnershipSupport.failure("PostgreSQL state commit failed", sqlFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL state commit failed", failure);
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
                if (!sameLease(current, lease)
                        || current.expiresAt().isBefore(PostgresqlOwnershipSupport.databaseNow(connection))) {
                    throw new OwnershipException("stale or expired ownership lease");
                }
            }
        }
    }

    private CommitResult<T> replayIfPresent(final Connection connection, final StateCommitRequest<T> request,
            final byte[] encoded) throws SQLException {
        String sql = "select expected_version, request_payload, new_version, result_payload from "
                + database.requestTable() + " where resource_type=? and resource_id=? and generation=? and request_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, request.resource(), 1);
            statement.setString(4, request.requestId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                if (result.getLong("expected_version") != request.expectedVersion().value()
                        || !Arrays.equals(result.getBytes("request_payload"), encoded)) {
                    throw new OwnershipException("request id was reused with a different payload");
                }
                return new CommitResult<>(false, true, new StateVersion(result.getLong("new_version")),
                        codec.decode(result.getBytes("result_payload")));
            }
        }
    }

    private StateVersion currentVersion(final Connection connection, final OwnershipResource resource) throws SQLException {
        String sql = "select version from " + database.stateTable()
                + " where resource_type=? and resource_id=? and generation=? for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new StateVersion(result.getLong(1)) : StateVersion.initial();
            }
        }
    }

    private void writeState(final Connection connection, final OwnershipResource resource,
            final StateVersion next, final byte[] payload) throws SQLException {
        String sql = "insert into " + database.stateTable() + " (resource_type, resource_id, generation, version, payload)"
                + " values (?, ?, ?, ?, ?) on conflict (resource_type, resource_id, generation) do update set version=?, payload=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, resource, 1);
            statement.setLong(4, next.value());
            statement.setBytes(5, payload);
            statement.setLong(6, next.value());
            statement.setBytes(7, payload);
            statement.executeUpdate();
        }
    }

    private void insertRequest(final Connection connection, final StateCommitRequest<T> request,
            final byte[] payload, final StateVersion next) throws SQLException {
        String sql = "insert into " + database.requestTable() + " (resource_type, resource_id, generation, request_id,"
                + " expected_version, request_payload, new_version, result_payload) values (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            PostgresqlOwnershipSupport.bindResource(statement, request.resource(), 1);
            statement.setString(4, request.requestId());
            statement.setLong(5, request.expectedVersion().value());
            statement.setBytes(6, payload);
            statement.setLong(7, next.value());
            statement.setBytes(8, payload);
            statement.executeUpdate();
        }
    }

    private static boolean sameLease(final OwnershipLease left, final OwnershipLease right) {
        return left.resource().equals(right.resource()) && left.owner().equals(right.owner())
                && left.epoch() == right.epoch();
    }
}
