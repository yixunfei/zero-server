package group.zn.zero.cache;

/**
 * 缓存统计快照。
 *
 * @param hitCount 命中次数。
 * @param missCount 未命中次数。
 * @param loadCount 加载次数。
 * @param loadFailureCount 加载失败次数。
 * @param putCount 写入次数。
 * @param invalidateCount 失效次数。
 * @author zn
 */
public record CacheStatistics(
        long hitCount,
        long missCount,
        long loadCount,
        long loadFailureCount,
        long putCount,
        long invalidateCount) {
}
