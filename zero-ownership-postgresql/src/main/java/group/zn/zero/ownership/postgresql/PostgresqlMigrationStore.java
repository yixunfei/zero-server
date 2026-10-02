package group.zn.zero.ownership.postgresql;

import group.zn.zero.ownership.MigrationId;
import group.zn.zero.ownership.MigrationCheckpointMetadata;
import group.zn.zero.ownership.MigrationRecord;
import group.zn.zero.ownership.MigrationState;
import group.zn.zero.ownership.MigrationStore;
import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.ProcessIncarnation;
import group.zn.zero.ownership.StateVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 将迁移状态机持久化到 PostgreSQL，支持进程重启后按记录恢复。 */
public final class PostgresqlMigrationStore implements MigrationStore {
    private static final Map<MigrationState, MigrationState[]> ALLOWED = allowedTransitions();
    private final PostgresqlOwnershipDatabase database;

    /** 创建迁移记录适配器。 */
    public PostgresqlMigrationStore(final PostgresqlOwnershipDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** 创建迁移；相同 ID 的相同元数据重试幂等。 */
    @Override
    public MigrationRecord create(final MigrationRecord record) {
        Objects.requireNonNull(record, "record");
        String insert = "insert into " + database.migrationTable() + " (migration_id, resource_type, resource_id, generation,"
                + " source_process_id, source_incarnation_id, target_process_id, target_incarnation_id, state,"
                + " checkpoint_version, updated_at, checkpoint_schema_version, checkpoint_codec_version, history_complete)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (migration_id) do nothing";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(insert)) {
            bindRecord(statement, record);
            statement.executeUpdate();
            MigrationRecord persisted = find(record.id()).orElseThrow();
            if (!sameMetadata(persisted, record)) {
                throw new OwnershipException("migration id was reused with different metadata");
            }
            return persisted;
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL migration create failed", failure);
        }
    }

    /** 查询迁移记录。 */
    @Override
    public Optional<MigrationRecord> find(final MigrationId id) {
        Objects.requireNonNull(id, "id");
        String sql = "select migration_id, resource_type, resource_id, generation, source_process_id, source_incarnation_id,"
                + " target_process_id, target_incarnation_id, state, checkpoint_version, updated_at, "
                + "checkpoint_schema_version, checkpoint_codec_version, history_complete from "
                + database.migrationTable() + " where migration_id=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRecord(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL migration read failed", failure);
        }
    }

    /** 在行锁内推进迁移状态；重复状态转换幂等，非法跳转拒绝。 */
    @Override
    public MigrationRecord transition(final MigrationId id, final MigrationState next,
            final StateVersion checkpointVersion, final Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(checkpointVersion, "checkpointVersion");
        Objects.requireNonNull(now, "now");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MigrationRecord current = lock(connection, id)
                        .orElseThrow(() -> new OwnershipException("migration does not exist"));
                if (current.state() == next) {
                    connection.commit();
                    return current;
                }
                if (!allowed(current.state(), next)) {
                    throw new OwnershipException("illegal migration state transition");
                }
                MigrationRecord updated = new MigrationRecord(current.id(), current.resource(), current.source(), current.target(),
                next, checkpointVersion, now, current.checkpointMetadata());
                String update = "update " + database.migrationTable()
                        + " set state=?, checkpoint_version=?, updated_at=? where migration_id=?";
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setString(1, next.name());
                    statement.setLong(2, checkpointVersion.value());
                    statement.setTimestamp(3, PostgresqlOwnershipSupport.timestamp(now));
                    statement.setString(4, id.value());
                    statement.executeUpdate();
                }
                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException failure) {
                PostgresqlOwnershipSupport.rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw PostgresqlOwnershipSupport.failure("PostgreSQL migration transition failed", failure);
        }
    }

    private Optional<MigrationRecord> lock(final Connection connection, final MigrationId id) throws SQLException {
        String sql = "select migration_id, resource_type, resource_id, generation, source_process_id, source_incarnation_id,"
                + " target_process_id, target_incarnation_id, state, checkpoint_version, updated_at, "
                + "checkpoint_schema_version, checkpoint_codec_version, history_complete from "
                + database.migrationTable() + " where migration_id=? for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRecord(result)) : Optional.empty();
            }
        }
    }

    private void bindRecord(final PreparedStatement statement, final MigrationRecord record) throws SQLException {
        statement.setString(1, record.id().value());
        statement.setString(2, record.resource().resourceType());
        statement.setString(3, record.resource().resourceId());
        statement.setLong(4, record.resource().generation());
        statement.setString(5, record.source().processId());
        statement.setString(6, record.source().incarnationId());
        statement.setString(7, record.target().processId());
        statement.setString(8, record.target().incarnationId());
        statement.setString(9, record.state().name());
        statement.setLong(10, record.checkpointVersion().value());
        statement.setTimestamp(11, PostgresqlOwnershipSupport.timestamp(record.updatedAt()));
        statement.setString(12, record.checkpointMetadata().schemaVersion());
        statement.setString(13, record.checkpointMetadata().codecVersion());
        statement.setBoolean(14, record.checkpointMetadata().historyComplete());
    }

    private MigrationRecord readRecord(final ResultSet result) throws SQLException {
        return new MigrationRecord(new MigrationId(result.getString("migration_id")),
                new OwnershipResource(result.getString("resource_type"), result.getString("resource_id"), result.getLong("generation")),
                new ProcessIncarnation(result.getString("source_process_id"), result.getString("source_incarnation_id")),
                new ProcessIncarnation(result.getString("target_process_id"), result.getString("target_incarnation_id")),
                MigrationState.valueOf(result.getString("state")),
                new StateVersion(result.getLong("checkpoint_version")), result.getTimestamp("updated_at").toInstant(),
                new MigrationCheckpointMetadata(result.getString("checkpoint_schema_version"),
                        result.getString("checkpoint_codec_version"), result.getBoolean("history_complete")));
    }

    private static boolean allowed(final MigrationState from, final MigrationState to) {
        for (MigrationState candidate : ALLOWED.get(from)) {
            if (candidate == to) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameMetadata(final MigrationRecord left, final MigrationRecord right) {
        return left.id().equals(right.id()) && left.resource().equals(right.resource())
                && left.source().equals(right.source()) && left.target().equals(right.target())
                && left.state() == right.state() && left.checkpointVersion().equals(right.checkpointVersion())
                && left.checkpointMetadata().equals(right.checkpointMetadata())
                && left.updatedAt().equals(PostgresqlOwnershipSupport.timestamp(right.updatedAt()).toInstant());
    }

    private static Map<MigrationState, MigrationState[]> allowedTransitions() {
        Map<MigrationState, MigrationState[]> map = new EnumMap<>(MigrationState.class);
        map.put(MigrationState.REQUESTED, new MigrationState[]{MigrationState.FROZEN, MigrationState.ABORTED});
        map.put(MigrationState.FROZEN, new MigrationState[]{MigrationState.CHECKPOINTED, MigrationState.ABORTED});
        map.put(MigrationState.CHECKPOINTED, new MigrationState[]{MigrationState.COMMITTED, MigrationState.ABORTED});
        map.put(MigrationState.COMMITTED, new MigrationState[]{MigrationState.ACTIVATED, MigrationState.ABORTED});
        map.put(MigrationState.ACTIVATED, new MigrationState[]{MigrationState.COMPLETED, MigrationState.ABORTED});
        map.put(MigrationState.COMPLETED, new MigrationState[0]);
        map.put(MigrationState.ABORTED, new MigrationState[0]);
        return Map.copyOf(map);
    }
}
