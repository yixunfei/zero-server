package group.zn.zero.runtime.data;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.PersistenceBindingExecutor;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** 在数据所属 Actor lane 捕获和确认快照；一个 scheduler 装配一个实例，管理器停止后关闭。 @author zn */
public final class ActorPersistenceBindingExecutor implements PersistenceBindingExecutor, AutoCloseable {
    /** 外部调度器。 */
    private final ActorScheduler scheduler;
    /** 独立持久化命令注册。 */
    private final ActorSubscription subscription;

    /** @param scheduler 调度器；注册失败直接传播，不拥有调度器资源。 */
    public ActorPersistenceBindingExecutor(final ActorScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
        subscription = scheduler.register(Capture.class, ActorHandler.sync((context, message) ->
                ((Capture<?>) message.payload()).run()));
    }

    /** {@inheritDoc} 未绑定动作直接执行；绑定动作必须异步等待，禁止在同 lane 内 join。 */
    @Override public <T> CompletionStage<T> capture(final DataThreadBinding binding, final Supplier<T> supplier) {
        Objects.requireNonNull(binding); Objects.requireNonNull(supplier);
        var result = new CompletableFuture<T>();
        try {
            if (binding.isUnbound()) result.complete(supplier.get());
            else scheduler.dispatch(new ActorMessage(new LaneKey(binding.type(), binding.value()),
                    new Capture<>(supplier, result))).whenComplete((ignored, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                    });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    /** 注销处理器；调用方必须先停止持久化任务并排空调度器。 */
    @Override public void close() { subscription.close(); }

    /** 在 lane 中执行的短时快照动作。 @param supplier 快照/确认动作。 @param result 结果。 @param <T> 类型。 */
    private record Capture<T>(Supplier<T> supplier, CompletableFuture<T> result) {
        private void run() {
            try { result.complete(supplier.get()); }
            catch (RuntimeException | Error failure) { result.completeExceptionally(failure); throw failure; }
        }
    }
}
