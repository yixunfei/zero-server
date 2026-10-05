package group.zn.zero.starter.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.scheduler.ScheduledTaskDefinition;
import group.zn.zero.core.scheduler.ScheduledTaskEvent;
import group.zn.zero.core.scheduler.ScheduledTaskEventType;
import group.zn.zero.core.scheduler.ScheduledTaskState;
import group.zn.zero.core.scheduler.SchedulerErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 可控执行器 Error 必须按拒绝路径清理预算，观察投递失败不能破坏业务状态。 @author zn */
class SchedulerExecutorErrorTest {
    /** 一次性任务拒绝后容量可复用，停止不会残留 inFlight。 */
    @Test void businessExecutorErrorReleasesLeaseAndReportsRejection() {
        try (var harness = new Harness()) {
            var first = harness.scheduler.scheduleOnce(definition(), Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            harness.workers.runAll();
            harness.workers.rejectNext = true;
            harness.timer.fire();
            harness.workers.runAll();
            assertEquals(ScheduledTaskState.REJECTED, first.snapshot().state());
            assertEquals(1, first.snapshot().rejectionCount());
            assertTrue(harness.events.stream().anyMatch(event -> event.eventType() == ScheduledTaskEventType.REJECTED));
            var second = harness.scheduler.scheduleOnce(definition(), Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            harness.workers.runAll();
            harness.timer.fire();
            harness.workers.runAll();
            assertEquals(ScheduledTaskState.COMPLETED, second.snapshot().state());
        }
    }

    /** 周期任务提交失败恢复 SCHEDULED，下一轮仍可执行。 */
    @Test void periodicExecutorErrorRearmsAndKeepsCapacityReusable() {
        try (var harness = new Harness()) {
            var handle = harness.scheduler.scheduleWithFixedDelay(definition(), Duration.ZERO,
                    Duration.ofSeconds(1), context -> CompletableFuture.completedFuture(null));
            harness.workers.runAll();
            harness.workers.rejectNext = true;
            harness.timer.fire();
            harness.workers.runAll();
            assertEquals(ScheduledTaskState.SCHEDULED, handle.snapshot().state());
            assertEquals(1, handle.snapshot().rejectionCount());
            harness.timer.fire();
            harness.workers.runAll();
            assertEquals(1, handle.snapshot().successCount());
            assertTrue(handle.cancel());
        }
    }

    /** 注册及 STARTED 观察投递 Error 走 fallback，已接受业务仍正常完成。 */
    @Test void observationSubmissionErrorDoesNotRejectOrFailBusiness() {
        try (var harness = new Harness()) {
            harness.workers.rejectNext = true;
            var calls = new AtomicInteger();
            var handle = harness.scheduler.scheduleOnce(definition(), Duration.ZERO, context -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            harness.timer.fire();
            harness.workers.rejectNext = true;
            harness.workers.runAll();
            assertEquals(1, calls.get());
            assertEquals(ScheduledTaskState.COMPLETED, handle.snapshot().state());
            assertEquals(0, handle.snapshot().failureCount());
        }
    }

    /** 初次 timer Error 释放任务槽位，重排 Error 明确终止且停止不被未完成租约阻塞。 */
    @Test void timerSubmissionErrorsReleaseInitialAndPeriodicTasks() {
        try (var harness = new Harness()) {
            harness.timer.rejectNext = true;
            var failure = assertThrows(ZeroException.class, () -> harness.scheduler.scheduleOnce(
                    definition(), Duration.ZERO, context -> CompletableFuture.completedFuture(null)));
            assertEquals(SchedulerErrorCode.TIMER_REJECTED, failure.errorCode());
            assertTrue(harness.scheduler.snapshots().isEmpty());
            var handle = harness.scheduler.scheduleWithFixedDelay(definition(), Duration.ZERO,
                    Duration.ofSeconds(1), context -> CompletableFuture.completedFuture(null));
            harness.workers.runAll();
            harness.timer.fire();
            harness.timer.rejectNext = true;
            harness.workers.runAll();
            assertEquals(ScheduledTaskState.REJECTED, handle.snapshot().state());
            assertEquals(SchedulerErrorCode.TIMER_REJECTED, handle.snapshot().lastErrorCode().orElseThrow());
            assertTrue(harness.scheduler.snapshots().isEmpty());
        }
    }

    private static ScheduledTaskDefinition definition() { return ScheduledTaskDefinition.defaults("error-boundary"); }

    /** 全部动作由当前测试线程显式推进，不创建工作线程。 */
    private static final class Harness implements AutoCloseable {
        /** 手动业务执行器。 */
        private final ErrorExecutor workers = new ErrorExecutor();
        /** 手动计时器。 */
        private final ErrorTimer timer = new ErrorTimer();
        /** 已成功投递的事件。 */
        private final List<ScheduledTaskEvent> events = new ArrayList<>();
        /** 受审计实例。 */
        private final LocalManagedScheduler scheduler;

        private Harness() {
            var sequence = new AtomicInteger();
            scheduler = new LocalManagedScheduler(workers, events::add,
                    new LocalManagedSchedulerOptions(1, 1, Duration.ofMillis(50), "error-test"),
                    Clock.systemUTC(), () -> 0L, () -> "id-" + sequence.incrementAndGet(), ignored -> timer);
            scheduler.start();
        }

        /** 结束前验证停止可完成，不允许隐藏租约泄漏。 */
        @Override public void close() { scheduler.stop(); workers.runAll(); }
    }

    /** 只在被明确设置的下一次提交抛 Error。 */
    private static final class ErrorExecutor implements Executor {
        /** 已接受工作。 */
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        /** 下一次提交故障开关。 */
        private boolean rejectNext;

        /** 顺序存储任务或模拟底层线程启动失败；只由测试线程访问。 */
        @Override public void execute(final Runnable command) {
            if (rejectNext) { rejectNext = false; throw new AssertionError("executor unavailable"); }
            tasks.add(command);
        }

        private void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    /** 拦截 schedule 防止创建真实计时线程。 */
    private static final class ErrorTimer extends ScheduledThreadPoolExecutor {
        /** 已接受计时任务。 */
        private final ArrayDeque<TimerFuture> tasks = new ArrayDeque<>();
        /** 下一次计时提交故障开关。 */
        private boolean rejectNext;

        private ErrorTimer() { super(1); }

        /** 记录一次性计时回调；不启动真实线程，线程不安全，仅测试线程调用。 */
        @Override public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
            if (rejectNext) { rejectNext = false; throw new AssertionError("timer unavailable"); }
            var future = new TimerFuture(command);
            tasks.add(future);
            return future;
        }

        private void fire() {
            TimerFuture next;
            do { next = tasks.remove(); } while (next.isCancelled());
            next.run();
        }
    }

    /** 可取消的手动计时 Future，不依赖墙钟。 */
    private static final class TimerFuture extends FutureTask<Void> implements RunnableScheduledFuture<Void> {
        private TimerFuture(final Runnable task) { super(task, null); }
        /** @return false；测试只安排一次性回调。 */
        @Override public boolean isPeriodic() { return false; }
        /** @return 零；测试直接触发。 */
        @Override public long getDelay(final TimeUnit unit) { return 0; }
        /** @return 相等；使用插入顺序队列。 */
        @Override public int compareTo(final Delayed other) { return 0; }
    }
}
