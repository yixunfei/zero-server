package group.zn.zero.discovery;

/**
 * 服务发现订阅句柄。
 *
 * @author zn
 */
@FunctionalInterface
public interface ServiceSubscription extends AutoCloseable {

    /**
     * 取消订阅。
     *
     * <p>该操作可能修改底层注册中心订阅状态；线程安全性由实现声明。
     *
     * @throws group.zn.zero.core.error.ZeroException 取消订阅失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    void close();
}
