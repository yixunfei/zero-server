package group.zn.zero.cache;

/**
 * 缓存健康状态快照。
 *
 * @param degraded 是否处于降级状态。
 * @param backendFailureCount 后端失败次数。
 * @param writeBackFailureCount 写回失败次数。
 * @param backlogCount 积压数量。
 * @author zn
 */
public record CacheHealthSnapshot(
        boolean degraded,
        long backendFailureCount,
        long writeBackFailureCount,
        long backlogCount) {

    /**
     * 创建缓存健康状态快照。
     *
     * @throws IllegalArgumentException 当计数为负数时抛出。
     */
    public CacheHealthSnapshot {
        requireNonNegative(backendFailureCount, "backendFailureCount");
        requireNonNegative(writeBackFailureCount, "writeBackFailureCount");
        requireNonNegative(backlogCount, "backlogCount");
    }

    private static void requireNonNegative(final long value, final String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
