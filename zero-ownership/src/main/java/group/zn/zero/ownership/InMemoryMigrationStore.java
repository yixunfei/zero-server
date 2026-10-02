package group.zn.zero.ownership;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 无外部依赖的迁移记录实现，验证幂等、恢复和状态单向推进。 */
public final class InMemoryMigrationStore implements MigrationStore {
    private static final Map<MigrationState, MigrationState[]> ALLOWED = allowedTransitions();
    private final Map<MigrationId, MigrationRecord> records = new HashMap<>();

    @Override
    public synchronized MigrationRecord create(final MigrationRecord record) {
        Objects.requireNonNull(record, "record");
        MigrationRecord existing = records.putIfAbsent(record.id(), record);
        if (existing != null && !existing.equals(record)) {
            throw new OwnershipException("migration id was reused with different metadata");
        }
        return existing == null ? record : existing;
    }

    @Override
    public synchronized Optional<MigrationRecord> find(final MigrationId id) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(id, "id")));
    }

    @Override
    public synchronized MigrationRecord transition(
            final MigrationId id,
            final MigrationState next,
            final StateVersion checkpointVersion,
            final Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(checkpointVersion, "checkpointVersion");
        Objects.requireNonNull(now, "now");
        MigrationRecord current = records.get(id);
        if (current == null) {
            throw new OwnershipException("migration does not exist");
        }
        if (current.state() == next) {
            return current;
        }
        if (!isAllowed(current.state(), next)) {
            throw new OwnershipException("illegal migration state transition");
        }
        MigrationRecord updated = new MigrationRecord(current.id(), current.resource(), current.source(), current.target(),
                next, checkpointVersion, now, current.checkpointMetadata());
        records.put(id, updated);
        return updated;
    }

    private static boolean isAllowed(final MigrationState from, final MigrationState to) {
        for (MigrationState candidate : ALLOWED.get(from)) {
            if (candidate == to) {
                return true;
            }
        }
        return false;
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
