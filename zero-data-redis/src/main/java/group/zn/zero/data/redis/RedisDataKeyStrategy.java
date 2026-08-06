package group.zn.zero.data.redis;

/**
 * Redis 数据 key 策略。
 *
 * <p>该策略只供 Redis Adapter 内部使用，业务代码必须通过 Repository / DataService 访问数据。
 *
 * @author zn
 */
public interface RedisDataKeyStrategy {

    /**
     * 生成对象快照 key。
     *
     * @param namespace 命名空间；不可为空。
     * @param collection 集合名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @return Redis key；不可为空；线程安全性由实现声明。
     */
    String dataKey(String namespace, String collection, String id);

    /**
     * 生成集合索引 key。
     *
     * @param namespace 命名空间；不可为空。
     * @param collection 集合名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @return Redis key；不可为空；线程安全性由实现声明。
     */
    String indexKey(String namespace, String collection, String id);

    /**
     * 生成追加日志 key。
     *
     * @param namespace 命名空间；不可为空。
     * @param collection 集合名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @return Redis key；不可为空；线程安全性由实现声明。
     */
    String journalKey(String namespace, String collection, String id);

    /**
     * 返回对象所属 bucket 标签。
     *
     * @param id 对象 ID；不可为空。
     * @return bucket 标签；不可为空；线程安全性由实现声明。
     */
    String bucketLabel(String id);
}
