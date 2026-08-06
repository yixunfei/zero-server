package group.zn.zero.examples.scheduler;

import group.zn.zero.actor.ActorContext;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.core.scheduler.ManagedScheduler;
import group.zn.zero.core.scheduler.ScheduledTaskDefinition;
import group.zn.zero.core.scheduler.ScheduledTaskFailurePolicy;
import group.zn.zero.core.scheduler.ScheduledTaskHandle;
import group.zn.zero.core.scheduler.ScheduledTaskState;
import group.zn.zero.core.scheduler.ScheduledTasks;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * 本地受管定时任务示例任务集。
 *
 * <p>任务集覆盖三类调度、失败策略、取消、异步远程 IO 模拟和 Actor 消息边界。它不创建线程池，
 * 不从 scheduler worker 修改玩家状态，也不在 worker 上等待异步结果。</p>
 *
 * @author zn
 */
final class ManagedSchedulerDemoTasks {

    /**
     * 单项示例最大等待时间。
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 受管定时任务调度器。
     */
    private final ManagedScheduler scheduler;

    /**
     * Actor 消息调度器。
     */
    private final ActorScheduler actorScheduler;

    /**
     * Starter 受管远程 IO 执行器。
     */
    private final Executor remoteIoExecutor;

    /**
     * 创建示例任务集。
     *
     * @param scheduler 受管定时任务调度器；不可为空且已经启动。
     * @param actorScheduler Actor 调度器；不可为空。
     * @param remoteIoExecutor 受管远程 IO 执行器；不可为空。
     * @throws NullPointerException 当任一依赖为空时抛出。
     */
    ManagedSchedulerDemoTasks(
            final ManagedScheduler scheduler,
            final ActorScheduler actorScheduler,
            final Executor remoteIoExecutor) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.actorScheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.remoteIoExecutor = Objects.requireNonNull(remoteIoExecutor, "remoteIoExecutor");
    }

    /**
     * 顺序运行受管定时任务完整演示。
     *
     * <p>本方法注册临时 Actor handler 并在返回前取消全部周期任务。方法会推进本地示例计数，
     * 非线程安全；不修改外部持久化数据。</p>
     *
     * @return 不可变任务结果；不可为空，线程安全。
     * @throws IllegalStateException 当任一示例在等待上限内未达到预期状态时抛出。
     */
    TaskSuiteResult run() {
        AtomicInteger actorValue = new AtomicInteger();
        AtomicReference<String> actorTraceId = new AtomicReference<>();
        AtomicReference<String> actorThread = new AtomicReference<>();
        CountDownLatch actorApplied = new CountDownLatch(1);
        ActorHandler handler = actorHandler(actorValue, actorTraceId, actorThread, actorApplied);
        try (ActorSubscription ignored = actorScheduler.register(ActorIncrement.class, handler)) {
            boolean once = runOnce();
            PeriodicResult fixedDelay = runFixedDelay();
            FixedRateResult fixedRate = runFixedRate();
            boolean defaultFailureStopped = runDefaultFailure();
            boolean continueRecovered = runContinueAfterFailure();
            ActorResult actor = runActorDispatch(actorValue, actorTraceId, actorThread, actorApplied);
            return new TaskSuiteResult(
                    once,
                    fixedDelay.completedTwice(),
                    fixedDelay.cancelIdempotent(),
                    fixedRate.skippedRunning(),
                    fixedRate.remoteIoThread(),
                    defaultFailureStopped,
                    continueRecovered,
                    actor.stateUpdated(),
                    actor.traceForwarded(),
                    actor.actorThread());
        }
    }

    private boolean runOnce() {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        ScheduledTaskHandle handle = scheduler.scheduleOnce(
                ScheduledTaskDefinition.defaults("example-once"),
                Duration.ZERO,
                ScheduledTasks.runnable(() -> {
                    executions.incrementAndGet();
                    completed.countDown();
                }));
        await(completed, "once execution");
        awaitState(handle, ScheduledTaskState.COMPLETED, "once completion");
        return executions.get() == 1
                && handle.snapshot().successCount() == 1;
    }

    private PeriodicResult runFixedDelay() {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(2);
        ScheduledTaskHandle handle = scheduler.scheduleWithFixedDelay(
                ScheduledTaskDefinition.defaults("example-fixed-delay"),
                Duration.ZERO,
                Duration.ofMillis(10),
                ScheduledTasks.runnable(() -> {
                    executions.incrementAndGet();
                    completed.countDown();
                }));
        await(completed, "two fixed-delay executions");
        boolean firstCancel = handle.cancel();
        boolean secondCancel = handle.cancel();
        awaitState(handle, ScheduledTaskState.CANCELLED, "fixed-delay cancellation");
        return new PeriodicResult(
                executions.get() >= 2 && handle.snapshot().successCount() >= 2,
                firstCancel && !secondCancel);
    }

    private FixedRateResult runFixedRate() {
        CountDownLatch remoteStarted = new CountDownLatch(1);
        CountDownLatch remoteCompleted = new CountDownLatch(1);
        AtomicReference<String> remoteThread = new AtomicReference<>();
        ScheduledTaskHandle handle = scheduler.scheduleAtFixedRate(
                ScheduledTaskDefinition.defaults("example-fixed-rate-slow"),
                Duration.ZERO,
                Duration.ofMillis(5),
                context -> simulateRemoteIo(remoteStarted, remoteCompleted, remoteThread));
        await(remoteStarted, "fixed-rate remote IO start");
        awaitCondition(
                () -> handle.snapshot().skippedRunningCount() > 0,
                "fixed-rate running skip");
        handle.cancel();
        await(remoteCompleted, "fixed-rate remote IO completion");
        awaitState(handle, ScheduledTaskState.CANCELLED, "fixed-rate cancellation");
        return new FixedRateResult(
                handle.snapshot().skippedRunningCount() > 0,
                remoteThread.get() != null && remoteThread.get().contains("-remote-io-"));
    }

    private CompletionStage<Void> simulateRemoteIo(
            final CountDownLatch started,
            final CountDownLatch completed,
            final AtomicReference<String> threadName) {
        return CompletableFuture.runAsync(() -> {
            threadName.set(Thread.currentThread().getName());
            started.countDown();
            try {
                TimeUnit.MILLISECONDS.sleep(80);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("simulated remote IO was interrupted", ex);
            } finally {
                completed.countDown();
            }
        }, remoteIoExecutor);
    }

    private boolean runDefaultFailure() {
        ScheduledTaskHandle handle = scheduler.scheduleWithFixedDelay(
                ScheduledTaskDefinition.defaults("example-default-failure"),
                Duration.ZERO,
                Duration.ofMillis(10),
                context -> CompletableFuture.failedFuture(
                        new IllegalStateException("expected example failure")));
        awaitState(handle, ScheduledTaskState.FAILED, "default failure termination");
        return handle.snapshot().failureCount() == 1
                && handle.snapshot().lastErrorCode().isPresent();
    }

    private boolean runContinueAfterFailure() {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledTaskDefinition definition = new ScheduledTaskDefinition(
                "example-continue-after-failure",
                ScheduledTaskFailurePolicy.CONTINUE);
        ScheduledTaskHandle handle = scheduler.scheduleWithFixedDelay(
                definition,
                Duration.ZERO,
                Duration.ofMillis(10),
                context -> {
                    if (attempts.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("expected first failure"));
                    }
                    return CompletableFuture.completedFuture(null);
                });
        awaitCondition(
                () -> handle.snapshot().failureCount() >= 1
                        && handle.snapshot().successCount() >= 1,
                "continue recovery");
        handle.cancel();
        awaitState(handle, ScheduledTaskState.CANCELLED, "continue cancellation");
        return attempts.get() >= 2
                && handle.snapshot().failureCount() >= 1
                && handle.snapshot().successCount() >= 1;
    }

    private ActorResult runActorDispatch(
            final AtomicInteger actorValue,
            final AtomicReference<String> actorTraceId,
            final AtomicReference<String> actorThread,
            final CountDownLatch actorApplied) {
        AtomicReference<String> schedulerTraceId = new AtomicReference<>();
        ScheduledTaskHandle handle = scheduler.scheduleOnce(
                ScheduledTaskDefinition.defaults("example-actor-dispatch"),
                Duration.ZERO,
                context -> {
                    schedulerTraceId.set(context.traceId());
                    return actorScheduler.dispatch(new ActorMessage(
                            context.executionId(),
                            LaneKey.player("example-player"),
                            context.traceId(),
                            new ActorIncrement(1)));
                });
        await(actorApplied, "Actor state update");
        awaitState(handle, ScheduledTaskState.COMPLETED, "Actor task completion");
        return new ActorResult(
                actorValue.get() == 1,
                Objects.equals(schedulerTraceId.get(), actorTraceId.get()),
                actorThread.get() != null && actorThread.get().contains("-actor-"));
    }

    private ActorHandler actorHandler(
            final AtomicInteger actorValue,
            final AtomicReference<String> actorTraceId,
            final AtomicReference<String> actorThread,
            final CountDownLatch actorApplied) {
        return (context, message) -> {
            ActorIncrement increment = (ActorIncrement) message.payload();
            actorValue.addAndGet(increment.delta());
            actorTraceId.set(context.traceId());
            actorThread.set(Thread.currentThread().getName());
            actorApplied.countDown();
            return CompletableFuture.completedFuture(null);
        };
    }

    private void awaitState(
            final ScheduledTaskHandle handle,
            final ScheduledTaskState expected,
            final String description) {
        awaitCondition(() -> handle.snapshot().state() == expected, description);
    }

    private void await(final CountDownLatch latch, final String description) {
        try {
            if (!latch.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out waiting for " + description);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + description, ex);
        }
    }

    private void awaitCondition(final BooleanSupplier condition, final String description) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + description);
            }
        }
    }

    /**
     * Actor lane 内执行的示例增量消息。
     *
     * @param delta 增量。
     * @author zn
     */
    private record ActorIncrement(int delta) {
    }

    /**
     * 周期任务演示结果。
     *
     * @param completedTwice fixed-delay 是否至少成功两次。
     * @param cancelIdempotent 取消是否幂等。
     * @author zn
     */
    private record PeriodicResult(boolean completedTwice, boolean cancelIdempotent) {
    }

    /**
     * fixed-rate 演示结果。
     *
     * @param skippedRunning 是否观察到运行中跳过。
     * @param remoteIoThread 模拟远程 IO 是否运行在受管 remote IO 执行域。
     * @author zn
     */
    private record FixedRateResult(boolean skippedRunning, boolean remoteIoThread) {
    }

    /**
     * Actor 投递演示结果。
     *
     * @param stateUpdated 状态是否只由 Actor handler 更新。
     * @param traceForwarded scheduler traceId 是否显式传入 ActorMessage。
     * @param actorThread handler 是否运行在 Actor 执行域。
     * @author zn
     */
    private record ActorResult(boolean stateUpdated, boolean traceForwarded, boolean actorThread) {
    }

    /**
     * 任务集可验证结果。
     *
     * @param onceCompleted once 是否只成功一次。
     * @param fixedDelayCompleted fixed-delay 是否成功运行至少两次。
     * @param cancelIdempotent 周期任务取消是否幂等。
     * @param fixedRateSkipped fixed-rate 是否跳过运行中节拍。
     * @param remoteIoThread 异步任务是否运行在 remote IO 执行域。
     * @param defaultFailureStopped 默认失败策略是否终止周期任务。
     * @param continueRecovered CONTINUE 是否在完整间隔后恢复。
     * @param actorUpdated Actor 状态是否更新。
     * @param traceForwarded traceId 是否显式传入 Actor 消息。
     * @param actorThread Actor handler 是否运行在 Actor 执行域。
     * @author zn
     */
    record TaskSuiteResult(
            boolean onceCompleted,
            boolean fixedDelayCompleted,
            boolean cancelIdempotent,
            boolean fixedRateSkipped,
            boolean remoteIoThread,
            boolean defaultFailureStopped,
            boolean continueRecovered,
            boolean actorUpdated,
            boolean traceForwarded,
            boolean actorThread) {
    }
}
