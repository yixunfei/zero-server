package group.zn.zero.ownership;

import java.util.Objects;

/** 状态提交结果；replayed 表示同一 requestId 的幂等重放。 */
public record CommitResult<T>(boolean committed, boolean replayed, StateVersion newVersion, T payload) {
    /** 校验结果字段。 */
    public CommitResult {
        Objects.requireNonNull(newVersion, "newVersion");
        if (committed == replayed) {
            throw new IllegalArgumentException("exactly one of committed and replayed must be true");
        }
    }
}
