package group.zn.zero.data.redis;

import java.util.Locale;
import java.util.Objects;

/**
 * 默认 Redis 数据 key 策略。
 *
 * <p>该策略使用可读前缀和 bucket hash tag，把同一 bucket 内的对象、索引和追加日志放入同一
 * Redis Cluster slot。业务层不应直接依赖这些 key。
 *
 * @author zn
 */
public final class DefaultRedisDataKeyStrategy implements RedisDataKeyStrategy {

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
     * 创建默认 Redis 数据 key 策略。
     */
    public DefaultRedisDataKeyStrategy() {
        this(DEFAULT_BUCKET_COUNT);
    }

    /**
     * 创建指定 bucket 数的 Redis 数据 key 策略。
     *
     * @param bucketCount bucket 数量；必须大于 0。
     * @throws IllegalArgumentException 当 bucket 数量不合法时抛出。
     */
    public DefaultRedisDataKeyStrategy(final int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        this.bucketCount = bucketCount;
        this.bucketWidth = Math.max(2, String.valueOf(bucketCount - 1).length());
    }

    /**
     * 生成对象快照 key。
     *
     * @param namespace 命名空间；不可为空。
     * @param collection 集合名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    @Override
    public String dataKey(final String namespace, final String collection, final String id) {
        String currentId = requireSegment(id, "id");
        return "zero:data:" + slotTag(namespace, collection, currentId) + ":" + currentId;
    }

    /**
     * 生成集合索引 key。
     *
     * @param namespace 命名空间；不可为空。
     * @param collection 集合名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    @Override
    public String indexKey(final String namespace, final String collection, final String id) {
        return "zero:index:" + slotTag(namespace, collection, requireSegment(id, "id")) + ":ids";
    }

    /**
     * 生成追加日志 key。
     *
     * @param namespace 命名空间；不可为空。
     * @param collection 集合名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    @Override
    public String journalKey(final String namespace, final String collection, final String id) {
        return "zero:journal:" + slotTag(namespace, collection, requireSegment(id, "id")) + ":stream";
    }

    /**
     * 返回 bucket 标签。
     *
     * @param id 对象 ID；不可为空。
     * @return bucket 标签；不可为空；线程安全。
     */
    @Override
    public String bucketLabel(final String id) {
        int bucket = Math.floorMod(requireSegment(id, "id").hashCode(), bucketCount);
        return String.format(Locale.ROOT, "%0" + bucketWidth + "d", bucket);
    }

    private String slotTag(final String namespace, final String collection, final String id) {
        return "{" + requireSegment(namespace, "namespace")
                + ":" + requireSegment(collection, "collection")
                + ":" + bucketLabel(id) + "}";
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
