package group.zn.zero.actor.handler;

import group.zn.zero.actor.ActorContext;
import group.zn.zero.actor.ActorMessage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Actor 消息处理器。
 *
 * @author zn
 */
@FunctionalInterface
public interface ActorHandler {

    /**
     * 处理 Actor 消息。
     *
     * @param context Actor 上下文；不可为空。
     * @param message Actor 消息；不可为空。
     * @return 处理完成信号；不可为空；同一 lane 内必须顺序完成。
     * @throws group.zn.zero.core.error.ZeroException 处理失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> handle(ActorContext context, ActorMessage message);

    /**
     * 创建同步 Actor 消息处理器。
     *
     * @param handler 同步处理逻辑；不可为空。
     * @return Actor 消息处理器；不可为空；线程安全性由 handler 决定。
     * @throws NullPointerException 当处理逻辑为空时抛出。
     */
    static ActorHandler sync(final SyncActorHandler handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        return (context, message) -> {
            handler.handle(context, message);
            return CompletableFuture.completedFuture(null);
        };
    }
}
