package group.zn.zero.event.handler;

import group.zn.zero.event.ZeroEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 事件处理器。
 *
 * @author zn
 */
@FunctionalInterface
public interface EventHandler {

    /**
     * 处理事件。
     *
     * @param event 事件对象；不可为空。
     * @return 处理完成信号；不可为空；是否异步、是否有序和线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 处理失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> handle(ZeroEvent event);

    /**
     * 创建同步事件处理器。
     *
     * @param handler 同步处理逻辑；不可为空。
     * @return 事件处理器；不可为空；线程安全性由 handler 决定。
     * @throws NullPointerException 当处理逻辑为空时抛出。
     */
    static EventHandler sync(final SyncEventHandler handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        return event -> {
            handler.handle(event);
            return CompletableFuture.completedFuture(null);
        };
    }
}
