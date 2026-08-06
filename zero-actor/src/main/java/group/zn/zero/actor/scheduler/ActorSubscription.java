package group.zn.zero.actor.scheduler;

/**
 * Actor 处理器注册句柄。
 *
 * @author zn
 */
@FunctionalInterface
public interface ActorSubscription extends AutoCloseable {

    /**
     * 取消注册。
     *
     * @throws group.zn.zero.core.error.ZeroException 取消失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    void close();
}
