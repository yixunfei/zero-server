package group.zn.zero.actor.handler;

import group.zn.zero.actor.ActorContext;
import group.zn.zero.actor.ActorMessage;

/**
 * 同步 Actor 消息处理器。
 *
 * @author zn
 */
@FunctionalInterface
public interface SyncActorHandler {

    /**
     * 同步处理 Actor 消息。
     *
     * @param context Actor 上下文；不可为空。
     * @param message Actor 消息；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 处理失败时抛出，必须绑定 ErrorCode。
     */
    void handle(ActorContext context, ActorMessage message);
}
