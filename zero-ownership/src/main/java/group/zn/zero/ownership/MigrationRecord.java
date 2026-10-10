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

    /** 比较幂等创建的业务元数据；调用时间不属于请求身份。线程安全，无副作用。 */
    public boolean sameCreationMetadata(final MigrationRecord other) {
        return other != null && id.equals(other.id) && resource.equals(other.resource)
                && source.equals(other.source) && target.equals(other.target)
                && state == other.state && checkpointVersion.equals(other.checkpointVersion)
                && checkpointMetadata.equals(other.checkpointMetadata);
    }

    /** 校验 checkpoint CAS 并返回新记录；无副作用，禁止所有权提交后修改恢复依据。 */
    public MigrationRecord updateCheckpoint(final StateVersion expectedVersion,
            final MigrationCheckpointMetadata expectedMetadata, final MigrationCheckpointMetadata metadata,
            final Instant now) {
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Objects.requireNonNull(expectedMetadata, "expectedMetadata");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(now, "now");
        if (state != MigrationState.FROZEN && state != MigrationState.CHECKPOINTED) {
            throw new OwnershipException("checkpoint metadata can only change before ownership commit");
        }
        if (!checkpointVersion.equals(expectedVersion) || !checkpointMetadata.equals(expectedMetadata)) {
            throw new OwnershipException("checkpoint metadata compare-and-set conflict");
        }
        return new MigrationRecord(id, resource, source, target, state, checkpointVersion, now, metadata);
    }
}
