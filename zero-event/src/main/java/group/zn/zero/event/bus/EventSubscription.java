package group.zn.zero.event.bus;

/**
 * 事件订阅句柄。
 *
 * @author zn
 */
@FunctionalInterface
public interface EventSubscription extends AutoCloseable {

    /**
     * 取消订阅。
     *
     * @throws group.zn.zero.core.error.ZeroException 取消失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    void close();
}
