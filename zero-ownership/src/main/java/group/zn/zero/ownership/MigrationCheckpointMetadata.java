package group.zn.zero.ownership;

import java.util.Objects;

/** 描述迁移快照的可恢复边界，不规定业务 payload 的具体编码。 */
public record MigrationCheckpointMetadata(
        String schemaVersion,
        String codecVersion,
        boolean historyComplete) {
    /** 校验元数据字段。 */
    public MigrationCheckpointMetadata {
        requireText(schemaVersion, "schemaVersion");
        requireText(codecVersion, "codecVersion");
    }

    /** 默认的完整历史快照元数据。 */
    public static MigrationCheckpointMetadata defaults() {
        return new MigrationCheckpointMetadata("1", "1", true);
    }

    private static void requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
