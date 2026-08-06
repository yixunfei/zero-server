package group.zn.zero.data.redis;

/**
 * Redis 缓存 key 策略。
 *
 * <p>该策略只用于 Redis 分布式缓存，不与数据快照、索引和追加日志 key 混用。
 *
 * @author zn
 */
public interface RedisCacheKeyStrategy {

    /**
     * 生成缓存值 key。
     *
     * @param namespace 缓存命名空间；不可为空。
     * @param cacheName 缓存名称；不可为空。
     * @param encodedKey 编码后的业务 key；不可为空。
     * @return Redis key；不可为空；线程安全性由实现声明。
     */
    String valueKey(String namespace, String cacheName, String encodedKey);

    /**
     * 生成缓存版本 key。
     *
     * @param namespace 缓存命名空间；不可为空。
     * @param cacheName 缓存名称；不可为空。
     * @param encodedKey 编码后的业务 key；不可为空。
     * @return Redis key；不可为空；线程安全性由实现声明。
     */
    String versionKey(String namespace, String cacheName, String encodedKey);

    /**
     * 生成缓存索引 key。
     *
     * @param namespace 缓存命名空间；不可为空。
     * @param cacheName 缓存名称；不可为空。
     * @return Redis key；不可为空；线程安全性由实现声明。
     */
    String indexKey(String namespace, String cacheName);

    /**
     * 返回业务 key 所属 bucket 标签。
     *
     * @param encodedKey 编码后的业务 key；不可为空。
     * @return bucket 标签；不可为空；线程安全性由实现声明。
     */
    String bucketLabel(String encodedKey);
}
