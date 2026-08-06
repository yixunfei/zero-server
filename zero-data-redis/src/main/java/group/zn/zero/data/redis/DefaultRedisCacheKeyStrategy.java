package group.zn.zero.data.redis;

import java.util.Locale;
import java.util.Objects;

/**
 * 默认 Redis 缓存 key 策略。
 *
 * <p>缓存 key 使用独立 `zero:cache` 前缀，并使用 Redis Cluster hash tag 让同一 key 的
 * value 和 version 位于同一 slot。
 *
 * @author zn
 */
public final class DefaultRedisCacheKeyStrategy implements RedisCacheKeyStrategy {

    /**
     * 默认 bucket 数。
     */
    private static final int DEFAULT_BUCKET_COUNT = 128;

    /**
     * bucket 数量。
     */
    private final int bucketCount;

    /**
     * bucket 标签宽度。
     */
    private final int bucketWidth;

    /**
     * 创建默认 Redis 缓存 key 策略。
     */
    public DefaultRedisCacheKeyStrategy() {
        this(DEFAULT_BUCKET_COUNT);
    }

    /**
     * 创建 Redis 缓存 key 策略。
     *
     * @param bucketCount bucket 数量；必须大于 0。
     * @throws IllegalArgumentException 当 bucket 数量非法时抛出。
     */
    public DefaultRedisCacheKeyStrategy(final int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        this.bucketCount = bucketCount;
        this.bucketWidth = Math.max(2, String.valueOf(bucketCount - 1).length());
    }

    /**
     * 生成缓存值 key。
     *
     * @param namespace 缓存命名空间；不可为空。
     * @param cacheName 缓存名称；不可为空。
     * @param encodedKey 编码后的业务 key；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    @Override
    public String valueKey(final String namespace, final String cacheName, final String encodedKey) {
        String key = requireSegment(encodedKey, "encodedKey");
        return "zero:cache:" + slotTag(namespace, cacheName, key) + ":" + key;
    }

    /**
     * 生成缓存版本 key。
     *
     * @param namespace 缓存命名空间；不可为空。
     * @param cacheName 缓存名称；不可为空。
     * @param encodedKey 编码后的业务 key；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    @Override
    public String versionKey(final String namespace, final String cacheName, final String encodedKey) {
        String key = requireSegment(encodedKey, "encodedKey");
        return "zero:cachever:" + slotTag(namespace, cacheName, key) + ":" + key;
    }

    /**
     * 生成缓存索引 key。
     *
     * @param namespace 缓存命名空间；不可为空。
     * @param cacheName 缓存名称；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    @Override
    public String indexKey(final String namespace, final String cacheName) {
        return "zero:cacheidx:{" + requireSegment(namespace, "namespace")
                + ":" + requireSegment(cacheName, "cacheName") + "}:buckets";
    }

    /**
     * 返回 bucket 标签。
     *
     * @param encodedKey 编码后的业务 key；不可为空。
     * @return bucket 标签；不可为空；线程安全。
     */
    @Override
    public String bucketLabel(final String encodedKey) {
        int bucket = Math.floorMod(requireSegment(encodedKey, "encodedKey").hashCode(), bucketCount);
        return String.format(Locale.ROOT, "%0" + bucketWidth + "d", bucket);
    }

    private String slotTag(final String namespace, final String cacheName, final String encodedKey) {
        return "{" + requireSegment(namespace, "namespace")
                + ":" + requireSegment(cacheName, "cacheName")
                + ":" + bucketLabel(encodedKey) + "}";
    }

    private String requireSegment(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (current.indexOf('{') >= 0 || current.indexOf('}') >= 0) {
            throw new IllegalArgumentException(name + " must not contain redis hash tag braces");
        }
        return current;
    }
}
