package group.zn.zero.ownership;

import java.util.Objects;

/** 带权威版本的状态快照。 */
public record StateSnapshot<T>(StateVersion version, T value) {
    /** 校验快照字段。 */
    public StateSnapshot {
        Objects.requireNonNull(version, "version");
    }
}
