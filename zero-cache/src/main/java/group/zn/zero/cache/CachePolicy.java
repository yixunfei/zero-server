package group.zn.zero.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * 缓存策略。
 *
 * @param ttl 正常缓存有效期。
 * @param negativeTtl 负缓存有效期。
 * @param ttlJitter TTL 抖动上限。
 * @param maxLocalEntries 本地缓存最大条目数。
 * @param maxConcurrentLoads 最大并发加载数。
 * @param allowStaleOnBackendFailure 后端失败时是否允许返回过期本地值。
 * @author zn
 */
public record CachePolicy(
        Duration ttl,
        Duration negativeTtl,
        Duration ttlJitter,
        int maxLocalEntries,
        int maxConcurrentLoads,
        boolean allowStaleOnBackendFailure) {

    /**
     * 默认缓存有效期。
     */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * 默认负缓存有效期。
     */
    public static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofSeconds(30);

    /**
     * 默认 TTL 抖动。
     */
    public static final Duration DEFAULT_TTL_JITTER = Duration.ofSeconds(5);

    /**
     * 默认本地缓存最大条目数。
     */
    public static final int DEFAULT_MAX_LOCAL_ENTRIES = 100_000;

    /**
     * 默认最大并发加载数。
     */
    public static final int DEFAULT_MAX_CONCURRENT_LOADS = 4096;

    /**
     * 创建缓存策略。
     *
     * @throws NullPointerException 当有效期为空时抛出。
     * @throws IllegalArgumentException 当有效期、容量或并发数非法时抛出。
     */
    public CachePolicy {
        ttl = positive(ttl, "ttl");
        negativeTtl = positive(negativeTtl, "negativeTtl");
        ttlJitter = nonNegative(ttlJitter, "ttlJitter");
        if (maxLocalEntries <= 0) {
            throw new IllegalArgumentException("maxLocalEntries must be positive");
        }
        if (maxConcurrentLoads <= 0) {
            throw new IllegalArgumentException("maxConcurrentLoads must be positive");
        }
    }

    /**
     * 创建默认策略。
     *
     * @return 默认缓存策略；不可为空；线程安全。
     */
    public static CachePolicy defaults() {
        return new CachePolicy(
                DEFAULT_TTL,
                DEFAULT_NEGATIVE_TTL,
                DEFAULT_TTL_JITTER,
                DEFAULT_MAX_LOCAL_ENTRIES,
                DEFAULT_MAX_CONCURRENT_LOADS,
                false);
    }

    /**
     * 使用指定 TTL 创建策略。
     *
     * @param ttl 正常缓存有效期；不可为空且必须大于 0。
     * @param negativeTtl 负缓存有效期；不可为空且必须大于 0。
     * @return 缓存策略；不可为空；线程安全。
     */
    public static CachePolicy of(final Duration ttl, final Duration negativeTtl) {
        return new CachePolicy(
                ttl,
                negativeTtl,
                DEFAULT_TTL_JITTER,
                DEFAULT_MAX_LOCAL_ENTRIES,
                DEFAULT_MAX_CONCURRENT_LOADS,
                false);
    }

    private static Duration positive(final Duration duration, final String name) {
        Duration current = Objects.requireNonNull(duration, name);
        if (current.isZero() || current.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return current;
    }

    private static Duration nonNegative(final Duration duration, final String name) {
        Duration current = Objects.requireNonNull(duration, name);
        if (current.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return current;
    }
}
