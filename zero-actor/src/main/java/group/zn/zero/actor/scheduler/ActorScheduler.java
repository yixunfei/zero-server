package group.zn.zero.actor.scheduler;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.concurrent.CompletionStage;

/**
 * Actor 消息调度器。
 *
 * @author zn
 */
public interface ActorScheduler {

    /**
     * 注册 Actor 消息处理器。
     *
     * @param payloadType 消息体类型；不可为空。
     * @param handler 消息处理器；不可为空。
     * @return 注册句柄；调用 close 后取消注册；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    ActorSubscription register(Class<?> payloadType, ActorHandler handler);

    /**
     * 提交 Actor 消息。
     *
     * @param message Actor 消息；不可为空。
     * @return 调度完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 调度失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> dispatch(ActorMessage message);
}
