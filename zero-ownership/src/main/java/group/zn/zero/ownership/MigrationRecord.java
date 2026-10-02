package group.zn.zero.ownership;

import java.time.Instant;
import java.util.Objects;

/** 可持久化、可重放的迁移记录。 */
public record MigrationRecord(
        MigrationId id,
        OwnershipResource resource,
        ProcessIncarnation source,
        ProcessIncarnation target,
        MigrationState state,
        StateVersion checkpointVersion,
        Instant updatedAt,
        MigrationCheckpointMetadata checkpointMetadata) {
    /** 兼容默认快照描述的构造方法。 */
    public MigrationRecord(final MigrationId id, final OwnershipResource resource,
            final ProcessIncarnation source, final ProcessIncarnation target, final MigrationState state,
            final StateVersion checkpointVersion, final Instant updatedAt) {
        this(id, resource, source, target, state, checkpointVersion, updatedAt,
                MigrationCheckpointMetadata.defaults());
    }

    /** 校验迁移记录。 */
    public MigrationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(checkpointVersion, "checkpointVersion");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(checkpointMetadata, "checkpointMetadata");
    }
}
