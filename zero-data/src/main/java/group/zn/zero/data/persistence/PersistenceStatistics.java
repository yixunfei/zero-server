package group.zn.zero.data.persistence;

/**
 * 持久化管理统计快照。
 *
 * @param dirtyCount 当前脏对象数量。
 * @param dirtyMarkCount 脏对象登记次数。
 * @param flushAttemptCount flush 尝试对象数量。
 * @param flushSuccessCount flush 成功对象数量。
 * @param flushFailureCount flush 失败对象数量。
 * @author zn
 */
public record PersistenceStatistics(
        int dirtyCount,
        long dirtyMarkCount,
        long flushAttemptCount,
        long flushSuccessCount,
        long flushFailureCount) {

    /**
     * 创建持久化管理统计快照。
     *
     * @throws IllegalArgumentException 当统计值为负数时抛出。
     */
    public PersistenceStatistics {
        if (dirtyCount < 0) {
            throw new IllegalArgumentException("dirtyCount must be non-negative");
        }
        requireNonNegative(dirtyMarkCount, "dirtyMarkCount");
        requireNonNegative(flushAttemptCount, "flushAttemptCount");
        requireNonNegative(flushSuccessCount, "flushSuccessCount");
        requireNonNegative(flushFailureCount, "flushFailureCount");
    }

    private static void requireNonNegative(final long value, final String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
