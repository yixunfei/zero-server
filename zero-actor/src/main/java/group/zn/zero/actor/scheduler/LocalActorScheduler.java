package group.zn.zero.actor.scheduler;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.concurrent.CompletionStage;

/**
 * 调用线程推进的本地调度器；共享有界队列、注册与恢复算法，不创建执行器或线程。
 * 同步处理在 dispatch 调用线程执行；异步恢复在完成回调线程执行。线程安全。
 * @author zn
 */
public final class LocalActorScheduler implements ActorScheduler, AutoCloseable {
    /** 使用直接执行器，复用同一调度算法与注册快照语义。 */
    private final ExecutorActorScheduler delegate;

    /** 创建默认有界调度器；线程安全。 */
    public LocalActorScheduler() { this(ActorSchedulerConfig.defaults()); }

    /**
     * 创建显式预算的本地调度器；线程安全。
     * @param config 正数预算，不可为空。
     * @throws NullPointerException 配置为空。
     */
    public LocalActorScheduler(final ActorSchedulerConfig config) {
        delegate = new ExecutorActorScheduler(Runnable::run, config);
    }

    /**
     * 注册有序处理器；线程安全。
     * @param payloadType 消息类型，不可为空。
     * @param handler 处理器，不可为空。
     * @return 幂等注销句柄；不可为空，线程安全。
     * @throws group.zn.zero.core.error.ZeroException 类型重复。
     */
    @Override public ActorSubscription register(final Class<?> payloadType, final ActorHandler handler) {
        return delegate.register(payloadType, handler);
    }

    /**
     * 提交消息；同步 handler 在调用线程完成，异步消息阻塞同 Lane 后续推进。
     * @param message 消息，不可为空。
     * @return 完成信号；线程安全，超限/关闭异步失败。
     * @throws group.zn.zero.core.error.ZeroException 未找到处理器。
     */
    @Override public CompletionStage<Void> dispatch(final ActorMessage message) {
        return delegate.dispatch(message);
    }

    /** @return 不可变观察快照，线程安全，不修改调度状态。 */
    public ActorSchedulerStatistics statistics() { return delegate.statistics(); }

    /** 幂等关闭准入并失败通知排队消息；线程安全，已运行 handler 等待自然完成。 */
    @Override public void close() { delegate.close(); }
}
