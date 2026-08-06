package group.zn.zero.data.model;

/**
 * 支持乐观锁版本的数据实体。
 *
 * @param <ID> 主键类型。
 * @author zn
 */
public interface VersionedEntity<ID> {

    /**
     * 返回实体主键。
     *
     * @return 主键；不可为空；线程安全性由实现声明。
     */
    ID id();

    /**
     * 返回实体版本号。
     *
     * @return 版本号；线程安全性由实现声明。
     */
    long version();

    /**
     * 返回设置新版本号后的实体副本。
     *
     * @param version 新版本号。
     * @return 带新版本号的实体；不可为空；线程安全性由实现声明。
     */
    VersionedEntity<ID> withVersion(long version);

    /**
     * 返回设置新主键后的实体副本。
     *
     * <p>该方法仅在对象映射配置了 ID 生成器且保存时实体 ID 为空时由框架调用。已有 ID 的业务对象
     * 不需要实现该方法；默认实现会拒绝隐式改写主键，避免把生成 ID 静默写入 payload。
     *
     * @param id 新主键；不可为空。
     * @return 带新主键的实体；不可为空；线程安全性由实现声明。
     * @throws UnsupportedOperationException 当前实体不支持由框架回填主键时抛出。
     */
    default VersionedEntity<ID> withId(final ID id) {
        throw new UnsupportedOperationException("entity id generation is not supported");
    }
}
