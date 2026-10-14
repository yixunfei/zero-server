package group.zn.zero.game;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 可选的本地 Actor 上下文组合桥；保留消息内容与 lane，不改变 Actor 内核依赖。
 * 注册和投递必须通过同一桥。目录只保留未完成消息，拒绝/完成后释放；不拥有底层调度器。
 * @author zn
 */
public final class ContextualActorScheduler implements ActorScheduler {
    /** 外部拥有的调度器。 */
    private final ActorScheduler delegate;
    /** 每次投递独立的消息身份，避免复用 messageId 时串身份。 */
    private final Map<ActorMessage, RequestExecution> pending = Collections.synchronizedMap(new IdentityHashMap<>());

    /** @param delegate 调度器；构造无资源副作用。 */
    public ContextualActorScheduler(final ActorScheduler delegate) { this.delegate = Objects.requireNonNull(delegate); }

    /** 注册原始 payload 处理器；线程安全，不改消息类型。
     * @param type 消息类型。 @param handler 处理器。 @return 注销句柄；底层注册失败直接传播。 */
    @Override public ActorSubscription register(final Class<?> type, final ActorHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return delegate.register(type, (context, message) -> {
            RequestExecution execution = pending.get(message);
            return execution == null ? RequestExecution.without(() -> handler.handle(context, message))
                    : execution.call(() -> handler.handle(context, message));
        });
    }

    /** 捕获当前可信范围后投递；线程安全，实际 handler 的完成阶段仍独占 lane。
     * @param message 原消息。 @return 实际调度完成阶段，不因交付取消提前完成。
     * @throws RuntimeException 底层同步拒绝。 */
    @Override public CompletionStage<Void> dispatch(final ActorMessage message) {
        var copy = new ActorMessage(message.messageId(), message.laneKey(), message.traceId(), message.payload());
        RequestExecution.current().ifPresent(scope -> pending.put(copy, scope));
        try {
            CompletionStage<Void> physical = delegate.dispatch(copy);
            return physical.whenComplete((ignored, failure) -> pending.remove(copy));
        } catch (RuntimeException | Error failure) {
            pending.remove(copy);
            throw failure;
        }
    }
}
