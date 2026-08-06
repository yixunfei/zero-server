package {{packageName}}.scheduler;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.core.scheduler.ManagedScheduler;
import group.zn.zero.core.scheduler.ScheduledTaskDefinition;
import group.zn.zero.core.scheduler.ScheduledTaskFailurePolicy;
import group.zn.zero.core.scheduler.ScheduledTaskHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 业务工程受管定时任务注册模块。
 *
 * <p>本模块不创建线程池、不暴露 timer，也不直接修改玩家或场景状态。远程调用由异步 gateway
 * 返回 CompletionStage；Actor 状态变化只通过消息投递。</p>
 *
 * @author zn
 */
public final class ManagedSchedulerModule implements AutoCloseable {

    /**
     * 框架受管调度器。
     */
    private final ManagedScheduler scheduler;

    /**
     * Actor 消息调度器。
     */
    private final ActorScheduler actorScheduler;

    /**
     * 业务异步维护端口。
     */
    private final AsyncMaintenanceGateway maintenanceGateway;

    /**
     * 本模块拥有的周期任务句柄。
     */
    private final List<ScheduledTaskHandle> periodicHandles = new ArrayList<>();

    /**
     * 模块是否已经注册周期任务。
     */
    private boolean started;

    /**
     * 创建任务注册模块。
     *
     * @param scheduler 已启动的受管调度器；不可为空。
     * @param actorScheduler Actor 消息调度器；不可为空。
     * @param maintenanceGateway 异步维护端口；不可为空，不能在实现中阻塞 scheduler worker。
     * @throws NullPointerException 当任一依赖为空时抛出。
     */
    public ManagedSchedulerModule(
            final ManagedScheduler scheduler,
            final ActorScheduler actorScheduler,
            final AsyncMaintenanceGateway maintenanceGateway) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.actorScheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.maintenanceGateway = Objects.requireNonNull(maintenanceGateway, "maintenanceGateway");
    }

    /**
     * 在 runtime 启动后注册一次性、fixed-delay 和 fixed-rate 示例任务。
     *
     * <p>本方法只注册任务并修改本模块句柄列表，必须在单线程生命周期装配阶段调用；重复调用会
     * fail-fast。默认失败策略会终止未来调度，只有明确可容忍单次失败的刷新任务才使用 CONTINUE。</p>
     *
     * @throws IllegalStateException 当模块重复启动时抛出。
     * @throws group.zn.zero.core.error.ZeroException 当 scheduler 未运行或容量不足时抛出。
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("managed scheduler module was already started");
        }
        started = true;
        scheduler.scheduleOnce(
                ScheduledTaskDefinition.defaults("application-warmup"),
                Duration.ZERO,
                context -> maintenanceGateway.warmup(context.traceId()));
        periodicHandles.add(scheduler.scheduleWithFixedDelay(
                ScheduledTaskDefinition.defaults("periodic-cleanup"),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                context -> maintenanceGateway.cleanup(context.traceId())));
        periodicHandles.add(scheduler.scheduleAtFixedRate(
                new ScheduledTaskDefinition(
                        "best-effort-refresh",
                        ScheduledTaskFailurePolicy.CONTINUE),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                context -> maintenanceGateway.refresh(context.traceId())));
    }

    /**
     * 注册一个到期后向玩家 Actor 投递消息的一次性任务。
     *
     * <p>taskName 保持低基数，不拼接 playerId。Scheduler worker 只构造并投递消息，业务状态由
     * Actor handler 在玩家 lane 内修改。</p>
     *
     * @param playerId 玩家标识；不可为空，仅进入 Actor lane key 和消息，不进入 scheduler 指标标签。
     * @param delay 到期延迟；不可为空且不得为负数。
     * @return 一次性任务句柄；不可为空、线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当参数非法、scheduler 未运行或容量不足时抛出。
     */
    public ScheduledTaskHandle schedulePlayerDeadline(
            final String playerId,
            final Duration delay) {
        String checkedPlayerId = Objects.requireNonNull(playerId, "playerId");
        if (checkedPlayerId.isBlank()) {
            throw new IllegalArgumentException("playerId must not be blank");
        }
        return scheduler.scheduleOnce(
                ScheduledTaskDefinition.defaults("player-deadline"),
                Objects.requireNonNull(delay, "delay"),
                context -> actorScheduler.dispatch(new ActorMessage(
                        context.executionId(),
                        LaneKey.player(checkedPlayerId),
                        context.traceId(),
                        new PlayerDeadlineReached())));
    }

    /**
     * 幂等取消本模块注册的未来周期任务。
     *
     * <p>本方法修改句柄列表，必须由单线程生命周期停止流程调用。已进入用户代码的任务不会被
     * interrupt，其 CompletionStage 仍由 scheduler 跟踪到终态。</p>
     */
    @Override
    public void close() {
        periodicHandles.forEach(ScheduledTaskHandle::cancel);
        periodicHandles.clear();
        started = false;
    }

    /**
     * 异步维护操作端口。
     *
     * <p>实现应使用框架受管 remote IO executor 或真正的异步客户端，并直接返回完成信号；
     * 禁止在 scheduler worker 中调用 join、get 或执行不可控阻塞 IO。</p>
     *
     * @author zn
     */
    public interface AsyncMaintenanceGateway {

        /**
         * 执行启动后的一次性异步预热。
         *
         * @param traceId scheduler 执行链路标识；不可为空。
         * @return 完成信号；不可为空，线程安全性由实现声明。
         */
        CompletionStage<Void> warmup(String traceId);

        /**
         * 执行异步清理。
         *
         * @param traceId scheduler 执行链路标识；不可为空。
         * @return 完成信号；不可为空，线程安全性由实现声明。
         */
        CompletionStage<Void> cleanup(String traceId);

        /**
         * 执行允许跨周期恢复的异步刷新。
         *
         * @param traceId scheduler 执行链路标识；不可为空。
         * @return 完成信号；不可为空，线程安全性由实现声明。
         */
        CompletionStage<Void> refresh(String traceId);
    }

    /**
     * 玩家到期 Actor 消息。
     *
     * @author zn
     */
    public record PlayerDeadlineReached() {
    }
}
