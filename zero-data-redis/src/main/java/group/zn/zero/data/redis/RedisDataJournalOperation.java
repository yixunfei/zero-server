package group.zn.zero.data.redis;

/**
 * Redis 数据追加日志操作类型。
 *
 * @author zn
 */
public enum RedisDataJournalOperation {

    /**
     * 保存或更新对象。
     */
    PUT,

    /**
     * 删除对象。
     */
    DELETE
}
