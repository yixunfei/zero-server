package group.zn.zero.data.persistence;

/**
 * 持久化 flush 结果。
 *
 * @param startedAtEpochMillis 开始时间戳。
 * @param finishedAtEpochMillis 结束时间戳。
 * @param selectedCount 本次选中的脏对象数量。
 * @param successCount 成功落库数量。
 * @param failureCount 失败数量。
 * @param remainingDirtyCount flush 完成后剩余脏对象数量。
 * @author zn
 */
public record PersistenceFlushResult(
        long startedAtEpochMillis,
        long finishedAtEpochMillis,
        int selectedCount,
        int successCount,
        int failureCount,
        int remainingDirtyCount) {

    /**
     * 创建持久化 flush 结果。
     *
     * @throws IllegalArgumentException 当数量为负数或时间戳非法时抛出。
     */
    public PersistenceFlushResult {
        if (startedAtEpochMillis < 0L || finishedAtEpochMillis < startedAtEpochMillis) {
            throw new IllegalArgumentException("invalid flush time range");
        }
        requireNonNegative(selectedCount, "selectedCount");
        requireNonNegative(successCount, "successCount");
        requireNonNegative(failureCount, "failureCount");
        requireNonNegative(remainingDirtyCount, "remainingDirtyCount");
    }

    private static void requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
