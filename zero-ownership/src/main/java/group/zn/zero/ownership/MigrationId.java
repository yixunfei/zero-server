package group.zn.zero.ownership;

import java.util.Objects;

/** 可恢复迁移的幂等身份。 */
public record MigrationId(String value) {
    /** 校验迁移身份。 */
    public MigrationId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("migration id must not be blank");
        }
    }
}
