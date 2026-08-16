package group.zn.zero.runtime.spi;

/**
 * provider 创建资源后立即登记的事务入口。
 *
 * @author zn
 */
public interface ResourceRegistrar {

    /**
     * 登记 runtime 拥有的资源并返回原对象。
     *
     * @param resource 资源；不可为空。
     * @param <T> 资源类型。
     * @return 原资源；不可为空。
     */
    <T extends AutoCloseable> T register(T resource);
}
