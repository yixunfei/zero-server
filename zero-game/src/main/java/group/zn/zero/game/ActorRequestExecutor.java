package group.zn.zero.game;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** 类型化业务动作的本地 Actor 入口；一个 scheduler 装配一个实例，异步完成前保持 lane 顺序。 @author zn */
public final class ActorRequestExecutor implements AutoCloseable {
    /** 自动恢复可信请求范围的调度桥。 */
    private final ActorScheduler scheduler;
    /** 注册句柄，关闭前调用方须排空在途请求。 */
    private final ActorSubscription subscription;

    /** @param scheduler 框架调度器；不拥有执行器，重复注册失败直接传播。 */
    public ActorRequestExecutor(final ActorScheduler scheduler) {
        this.scheduler = new ContextualActorScheduler(Objects.requireNonNull(scheduler));
        subscription = this.scheduler.register(Command.class, (context, message) -> ((Command<?>) message.payload()).run());
    }

    /** 在指定 lane 执行业务，自动绑定入口范围；业务依赖必须异步，不能阻塞 Actor。
     * @param lane 执行域。 @param action 返回物理完成阶段的动作。 @param <T> 类型。
     * @return 独立交付阶段；取消不会提前释放物理任务所占 lane；线程安全。
     * @throws NullPointerException 参数为空。 */
    public <T> CompletionStage<T> submit(final LaneKey lane, final Supplier<? extends CompletionStage<T>> action) {
        Objects.requireNonNull(action);
        var result = new CompletableFuture<T>();
        RequestExecution scope = RequestExecution.current().orElse(null);
        try {
            scheduler.dispatch(new ActorMessage(lane, scope == null ? "background" : scope.identity().traceId(),
                    new Command<>(action, result))).whenComplete((ignored, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                    });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return scope == null ? result : scope.deliver(result);
    }

    /** 排空后注销处理器，不关闭共享调度器。 */
    @Override public void close() { subscription.close(); }

    /** 一个已准入动作。 @param action 业务。 @param result 结果。 @param <T> 类型。 */
    private record Command<T>(Supplier<? extends CompletionStage<T>> action, CompletableFuture<T> result) {
        private CompletionStage<Void> run() {
            return Objects.requireNonNull(action.get(), "business stage").handle((value, failure) -> {
                if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                return (Void) null;
            });
        }
    }
}
