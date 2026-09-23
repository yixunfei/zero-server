package group.zn.zero.starter.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.core.scheduler.ScheduleType;
import group.zn.zero.core.scheduler.ScheduledTaskDefinition;
import group.zn.zero.core.scheduler.ScheduledTaskEvent;
import group.zn.zero.core.scheduler.ScheduledTaskEventType;
import group.zn.zero.core.scheduler.ScheduledTaskFailurePolicy;
import group.zn.zero.core.scheduler.ScheduledTaskHandle;
import group.zn.zero.core.scheduler.ScheduledTaskObserver;
import group.zn.zero.core.scheduler.ScheduledTaskSnapshot;
import group.zn.zero.core.scheduler.ScheduledTaskState;
import group.zn.zero.core.scheduler.SchedulerErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 本地受管定时任务状态机、异步终态、容量、取消和停止 focused tests。
 *
 * <p>计时行为使用手动 one-shot timer、受控 monotonic 时间和手动 executor，不依赖固定 sleep。
 * 线程隔离与 Starter L1 装配由独立 factory focused tests 覆盖。</p>
 *
 * @author zn
 */
class LocalManagedSchedulerTest {

    /**
     * 测试固定墙钟。
     */
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-27T00:00:00Z"),
            ZoneOffset.UTC);

    /**
     * 验证负延迟、零周期和 Duration 纳秒溢出在创建 handle 前绑定非法参数 ErrorCode。
     */
    @Test
    void shouldRejectInvalidScheduleDurations() {
        Harness harness = Harness.start(4, 2);
        try {
            ZeroException negative = assertThrows(
                    ZeroException.class,
                    () -> harness.scheduler.scheduleOnce(
                            definition("negative-delay"),
                            Duration.ofNanos(-1L),
                            context -> CompletableFuture.completedFuture(null)));
            ZeroException zeroPeriod = assertThrows(
                    ZeroException.class,
                    () -> harness.scheduler.scheduleAtFixedRate(
                            definition("zero-period"),
                            Duration.ZERO,
                            Duration.ZERO,
                            context -> CompletableFuture.completedFuture(null)));
            ZeroException overflow = assertThrows(
                    ZeroException.class,
                    () -> harness.scheduler.scheduleWithFixedDelay(
                            definition("overflow-delay"),
                            Duration.ZERO,
                            Duration.ofSeconds(Long.MAX_VALUE),
                            context -> CompletableFuture.completedFuture(null)));

            assertSame(SchedulerErrorCode.INVALID_ARGUMENT, negative.errorCode());
            assertSame(SchedulerErrorCode.INVALID_ARGUMENT, zeroPeriod.errorCode());
            assertSame(SchedulerErrorCode.INVALID_ARGUMENT, overflow.errorCode());
            assertTrue(harness.scheduler.snapshots().isEmpty());
            assertEquals(0, harness.timer.pendingCount());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证 once 成功终止、清理活动表和释放 maxTasks 槽位。
     */
    @Test
    void shouldCompleteOnceAndReleaseTaskSlot() {
        Harness harness = Harness.start(1, 1);
        try {
            AtomicInteger calls = new AtomicInteger();
            ScheduledTaskHandle first = harness.scheduler.scheduleOnce(
                    definition("once-success"),
                    Duration.ZERO,
                    context -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();

            assertEquals(1, calls.get());
            assertSnapshot(first.snapshot(), ScheduledTaskState.COMPLETED, 1L, 1L, 0L);
            assertTrue(harness.scheduler.snapshots().isEmpty());

            ScheduledTaskHandle second = harness.scheduler.scheduleOnce(
                    definition("slot-reused"),
                    Duration.ofNanos(1L),
                    context -> CompletableFuture.completedFuture(null));
            assertNotEquals(first.taskId(), second.taskId());
            second.cancel();
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证 fixed-delay 从异步 stage 真正完成后才安排下一次 timer。
     */
    @Test
    void shouldRearmFixedDelayAfterAsyncCompletion() {
        Harness harness = Harness.start(4, 2);
        try {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            ScheduledTaskHandle handle = harness.scheduler.scheduleWithFixedDelay(
                    definition("async-delay"),
                    Duration.ZERO,
                    Duration.ofNanos(100L),
                    context -> completion);
            harness.runRegistrationAndNextTimer();
            harness.workers.runNext();
            harness.workers.runAll();

            assertSame(ScheduledTaskState.RUNNING, handle.snapshot().state());
            assertEquals(0, harness.timer.pendingCount());

            harness.nanoTime.set(250L);
            completion.complete(null);
            harness.workers.runAll();

            assertSame(ScheduledTaskState.SCHEDULED, handle.snapshot().state());
            assertEquals(1, harness.timer.pendingCount());
            assertEquals(1L, handle.snapshot().successCount());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证 fixed-rate 跳过多个迟到 occurrence，且只保留一个未来 timer。
     */
    @Test
    void shouldSkipLateFixedRateOccurrencesWithoutCatchUp() {
        Harness harness = Harness.start(4, 2);
        try {
            AtomicInteger calls = new AtomicInteger();
            ScheduledTaskHandle handle = harness.scheduler.scheduleAtFixedRate(
                    definition("late-rate"),
                    Duration.ZERO,
                    Duration.ofNanos(100L),
                    context -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });
            harness.workers.runAll();
            harness.nanoTime.set(350L);
            assertTrue(harness.timer.runNext());
            harness.workers.runAll();

            assertEquals(1, calls.get());
            assertEquals(3L, handle.snapshot().skippedLateCount());
            assertEquals(1, harness.timer.pendingCount());
            List<ScheduledTaskEvent> lateEvents = harness.eventsOf(ScheduledTaskEventType.SKIPPED_LATE);
            assertEquals(1, lateEvents.size());
            assertEquals(3L, lateEvents.getFirst().occurrenceCount());
            assertFalse(lateEvents.getFirst().hasExecution());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证 fixed-rate 的未完成 stage 覆盖整个 in-flight 生命周期并跳过运行中节拍。
     */
    @Test
    void shouldSkipFixedRateWhilePreviousStageIsRunning() {
        Harness harness = Harness.start(4, 2);
        try {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            ScheduledTaskHandle handle = harness.scheduler.scheduleAtFixedRate(
                    definition("running-rate"),
                    Duration.ZERO,
                    Duration.ofNanos(100L),
                    context -> completion);
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();

            harness.nanoTime.set(100L);
            assertTrue(harness.timer.runNext());
            assertEquals(1L, handle.snapshot().skippedRunningCount());
            assertEquals(1, harness.timer.pendingCount());

            completion.complete(null);
            harness.workers.runAll();
            List<ScheduledTaskEvent> skipped = harness.eventsOf(ScheduledTaskEventType.SKIPPED_RUNNING);
            assertEquals(1, skipped.size());
            assertEquals(1L, skipped.getFirst().occurrenceCount());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证 maxTasks 与 maxInFlight 分别拒绝注册和一次性到期执行。
     */
    @Test
    void shouldEnforceTaskAndInFlightBudgets() {
        Harness taskLimited = Harness.start(1, 1);
        try {
            ScheduledTaskHandle occupied = taskLimited.scheduler.scheduleOnce(
                    definition("occupied"),
                    Duration.ofNanos(10L),
                    context -> CompletableFuture.completedFuture(null));
            ZeroException limit = assertThrows(
                    ZeroException.class,
                    () -> taskLimited.scheduler.scheduleOnce(
                            definition("overflow"),
                            Duration.ZERO,
                            context -> CompletableFuture.completedFuture(null)));
            assertSame(SchedulerErrorCode.TASK_LIMIT_EXCEEDED, limit.errorCode());
            occupied.cancel();
        } finally {
            taskLimited.stop();
        }

        Harness inFlightLimited = Harness.start(4, 1);
        try {
            CompletableFuture<Void> firstCompletion = new CompletableFuture<>();
            ScheduledTaskHandle first = inFlightLimited.scheduler.scheduleOnce(
                    definition("first"),
                    Duration.ZERO,
                    context -> firstCompletion);
            ScheduledTaskHandle rejected = inFlightLimited.scheduler.scheduleOnce(
                    definition("second"),
                    Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            inFlightLimited.workers.runAll();
            assertTrue(inFlightLimited.timer.runNext());
            assertTrue(inFlightLimited.timer.runNext());

            assertSame(ScheduledTaskState.REJECTED, rejected.snapshot().state());
            assertSame(SchedulerErrorCode.EXECUTOR_REJECTED, rejected.snapshot().lastErrorCode().orElseThrow());
            assertEquals(1L, rejected.snapshot().rejectionCount());

            inFlightLimited.workers.runNext();
            firstCompletion.complete(null);
            inFlightLimited.workers.runAll();
            assertSame(ScheduledTaskState.COMPLETED, first.snapshot().state());
        } finally {
            inFlightLimited.stop();
        }
    }

    /**
     * 验证 executor 主动拒绝不会泄漏预算，once 仅计一次 rejection。
     */
    @Test
    void shouldHandleExecutorRejectionExactlyOnce() {
        ManualExecutor workers = new ManualExecutor();
        Harness harness = Harness.start(2, 1, workers, ScheduledTaskObserver.noOp(), Harness.ids());
        try {
            ScheduledTaskHandle handle = harness.scheduler.scheduleOnce(
                    definition("executor-reject"),
                    Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            workers.runAll();
            workers.reject = true;
            assertTrue(harness.timer.runNext());

            assertSame(ScheduledTaskState.REJECTED, handle.snapshot().state());
            assertEquals(1L, handle.snapshot().rejectionCount());
            workers.reject = false;
            ScheduledTaskHandle next = harness.scheduler.scheduleOnce(
                    definition("after-reject"),
                    Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            next.cancel();
        } finally {
            workers.reject = false;
            harness.stop();
        }
    }

    /**
     * 验证同步异常、null stage 和业务 ZeroException 进入统一失败路径。
     */
    @Test
    void shouldHandleSynchronousTaskFailures() {
        Harness harness = Harness.start(4, 2);
        try {
            ScheduledTaskHandle runtimeFailure = runOnce(harness, "runtime", context -> {
                throw new IllegalStateException("boom");
            });
            ScheduledTaskHandle nullStage = runOnce(harness, "null-stage", context -> null);
            ScheduledTaskHandle businessFailure = runOnce(harness, "business", context -> {
                throw ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, "business", null);
            });

            assertSame(ScheduledTaskState.FAILED, runtimeFailure.snapshot().state());
            assertSame(SchedulerErrorCode.TASK_EXECUTION_FAILED,
                    runtimeFailure.snapshot().lastErrorCode().orElseThrow());
            assertSame(ScheduledTaskState.FAILED, nullStage.snapshot().state());
            assertSame(SystemErrorCode.INVALID_ARGUMENT,
                    businessFailure.snapshot().lastErrorCode().orElseThrow());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证默认周期失败终止，而显式 CONTINUE 等待完整 fixed-delay 后续排。
     */
    @Test
    void shouldApplyPeriodicFailurePolicies() {
        Harness harness = Harness.start(4, 2);
        try {
            ScheduledTaskHandle cancelled = harness.scheduler.scheduleWithFixedDelay(
                    definition("default-failure"),
                    Duration.ZERO,
                    Duration.ofNanos(100L),
                    context -> CompletableFuture.failedFuture(new IllegalStateException("failed")));
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();
            assertSame(ScheduledTaskState.FAILED, cancelled.snapshot().state());

            AtomicInteger attempts = new AtomicInteger();
            ScheduledTaskHandle continued = harness.scheduler.scheduleWithFixedDelay(
                    new ScheduledTaskDefinition("continue-failure", ScheduledTaskFailurePolicy.CONTINUE),
                    Duration.ZERO,
                    Duration.ofNanos(100L),
                    context -> attempts.getAndIncrement() == 0
                            ? CompletableFuture.failedFuture(new IllegalStateException("first"))
                            : CompletableFuture.completedFuture(null));
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();
            assertSame(ScheduledTaskState.SCHEDULED, continued.snapshot().state());
            assertEquals(1, harness.timer.pendingCount());
            assertTrue(harness.timer.runNext());
            harness.workers.runAll();
            assertEquals(2, attempts.get());
            assertEquals(1L, continued.snapshot().failureCount());
            assertEquals(1L, continued.snapshot().successCount());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证同步与异步 Error 都终止周期，且同步 Error 在状态落定后重新抛出。
     */
    @Test
    void shouldTerminateOnFatalErrors() {
        Harness syncHarness = Harness.start(2, 1);
        try {
            ScheduledTaskHandle sync = syncHarness.scheduler.scheduleWithFixedDelay(
                    new ScheduledTaskDefinition("sync-error", ScheduledTaskFailurePolicy.CONTINUE),
                    Duration.ZERO,
                    Duration.ofNanos(10L),
                    context -> {
                        throw new TestFatalError();
                    });
            syncHarness.workers.runAll();
            assertTrue(syncHarness.timer.runNext());
            assertThrows(TestFatalError.class, syncHarness.workers::runNext);
            assertSame(ScheduledTaskState.FAILED, sync.snapshot().state());
        } finally {
            syncHarness.stop();
        }

        Harness asyncHarness = Harness.start(2, 1);
        try {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            ScheduledTaskHandle async = asyncHarness.scheduler.scheduleWithFixedDelay(
                    new ScheduledTaskDefinition("async-error", ScheduledTaskFailurePolicy.CONTINUE),
                    Duration.ZERO,
                    Duration.ofNanos(10L),
                    context -> failed);
            asyncHarness.runRegistrationAndNextTimer();
            asyncHarness.workers.runAll();
            failed.completeExceptionally(new TestFatalError());
            asyncHarness.workers.runAll();
            assertSame(ScheduledTaskState.FAILED, async.snapshot().state());
            assertEquals(0, asyncHarness.timer.pendingCount());
        } finally {
            asyncHarness.stop();
        }
    }

    /**
     * 验证完成回调内联后 whenComplete 再抛异常也只完成和释放一次。
     */
    @Test
    void shouldCompleteLeaseOnlyOnceForHostileStage() {
        Harness harness = Harness.start(1, 1);
        try {
            CallbackThenThrowFuture stage = new CallbackThenThrowFuture();
            stage.complete(null);
            ScheduledTaskHandle handle = runOnce(harness, "hostile-stage", context -> stage);

            assertSnapshot(handle.snapshot(), ScheduledTaskState.COMPLETED, 1L, 1L, 0L);
            ScheduledTaskHandle second = harness.scheduler.scheduleOnce(
                    definition("lease-reused"),
                    Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            second.cancel();
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证执行上下文 ID 创建失败不会泄漏任务或 in-flight 槽位。
     */
    @Test
    void shouldCleanUpWhenExecutionContextCreationFails() {
        AtomicInteger sequence = new AtomicInteger();
        Supplier<String> failingIds = () -> sequence.incrementAndGet() == 1 ? "task-1" : null;
        Harness harness = Harness.start(
                1,
                1,
                new ManualExecutor(),
                ScheduledTaskObserver.noOp(),
                failingIds);
        try {
            ScheduledTaskHandle handle = harness.scheduler.scheduleOnce(
                    definition("setup-failure"),
                    Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();

            assertSame(ScheduledTaskState.FAILED, handle.snapshot().state());
            assertTrue(harness.scheduler.snapshots().isEmpty());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证取消分别阻止未到期和已提交未开始的任务，并且幂等。
     */
    @Test
    void shouldCancelBeforeUserCodeStarts() {
        Harness beforeDue = Harness.start(2, 1);
        try {
            AtomicInteger calls = new AtomicInteger();
            ScheduledTaskHandle handle = beforeDue.scheduler.scheduleOnce(
                    definition("before-due"),
                    Duration.ofNanos(10L),
                    context -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });
            assertTrue(handle.cancel());
            assertFalse(handle.cancel());
            assertFalse(beforeDue.timer.runNext());
            beforeDue.workers.runAll();
            assertEquals(0, calls.get());
            assertSame(ScheduledTaskState.CANCELLED, handle.snapshot().state());
        } finally {
            beforeDue.stop();
        }

        Harness afterSubmit = Harness.start(2, 1);
        try {
            AtomicInteger calls = new AtomicInteger();
            ScheduledTaskHandle handle = afterSubmit.scheduler.scheduleOnce(
                    definition("after-submit"),
                    Duration.ZERO,
                    context -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });
            afterSubmit.workers.runAll();
            assertTrue(afterSubmit.timer.runNext());
            assertTrue(handle.cancel());
            afterSubmit.workers.runAll();
            assertEquals(0, calls.get());
            assertSame(ScheduledTaskState.CANCELLED, handle.snapshot().state());
        } finally {
            afterSubmit.stop();
        }
    }

    /**
     * 验证运行中取消不强制完成或中断用户 stage，但完成后进入 CANCELLED。
     */
    @Test
    void shouldNotInterruptRunningStageOnCancel() {
        Harness harness = Harness.start(2, 1);
        try {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            ScheduledTaskHandle handle = harness.scheduler.scheduleAtFixedRate(
                    definition("cancel-running"),
                    Duration.ZERO,
                    Duration.ofNanos(10L),
                    context -> completion);
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();

            assertTrue(handle.cancel());
            assertFalse(completion.isCancelled());
            assertFalse(completion.isDone());
            assertSame(ScheduledTaskState.RUNNING, handle.snapshot().state());

            completion.complete(null);
            harness.workers.runAll();
            assertSame(ScheduledTaskState.CANCELLED, handle.snapshot().state());
            assertEquals(0, harness.timer.pendingCount());
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证 stop 后拒绝新注册，且未完成 stage 触发绑定 ErrorCode 的停止超时。
     */
    @Test
    void shouldEnforceStopAdmissionAndTimeout() {
        Harness stopped = Harness.start(2, 1);
        stopped.stop();
        ZeroException notRunning = assertThrows(
                ZeroException.class,
                () -> stopped.scheduler.scheduleOnce(
                        definition("after-stop"),
                        Duration.ZERO,
                        context -> CompletableFuture.completedFuture(null)));
        assertSame(SchedulerErrorCode.NOT_RUNNING, notRunning.errorCode());

        Harness timedOut = Harness.start(2, 1, Duration.ofMillis(10L));
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ScheduledTaskHandle handle = timedOut.scheduler.scheduleOnce(
                definition("stop-timeout"),
                Duration.ZERO,
                context -> completion);
        timedOut.runRegistrationAndNextTimer();
        timedOut.workers.runAll();

        ZeroException timeout = assertThrows(ZeroException.class, timedOut.scheduler::stop);
        assertSame(SchedulerErrorCode.STOP_TIMEOUT, timeout.errorCode());
        assertSame(LifecycleState.FAILED, timedOut.scheduler.state());
        completion.complete(null);
        timedOut.workers.runAll();
        assertSame(ScheduledTaskState.CANCELLED, handle.snapshot().state());
        List<ScheduledTaskEvent> timeoutEvents = timedOut.eventsOf(ScheduledTaskEventType.STOP_TIMEOUT);
        assertEquals(1, timeoutEvents.size());
        assertSame(SchedulerErrorCode.STOP_TIMEOUT, timeoutEvents.getFirst().errorCode());
    }

    /**
     * 验证 observer 异常被隔离，不改变一次性任务结果，也不阻断后续 timer。
     */
    @Test
    void shouldIsolateObserverFailure() {
        AtomicInteger observations = new AtomicInteger();
        ScheduledTaskObserver failingObserver = event -> {
            observations.incrementAndGet();
            throw new IllegalStateException("observer failed");
        };
        Harness harness = Harness.start(2, 1, new ManualExecutor(), failingObserver, Harness.ids());
        try {
            ScheduledTaskHandle first = runOnce(
                    harness,
                    "observer-first",
                    context -> CompletableFuture.completedFuture(null));
            ScheduledTaskHandle second = runOnce(
                    harness,
                    "observer-second",
                    context -> CompletableFuture.completedFuture(null));

            assertSame(ScheduledTaskState.COMPLETED, first.snapshot().state());
            assertSame(ScheduledTaskState.COMPLETED, second.snapshot().state());
            assertTrue(observations.get() >= 6);
        } finally {
            harness.stop();
        }
    }

    /**
     * 验证同一 handle 的 taskId 稳定，每次执行的 executionId 与 traceId 唯一。
     */
    @Test
    void shouldKeepTaskIdAndRotateExecutionIdentity() {
        Harness harness = Harness.start(2, 1);
        try {
            List<String> taskIds = new ArrayList<>();
            Set<String> executionIds = new HashSet<>();
            Set<String> traceIds = new HashSet<>();
            ScheduledTaskHandle handle = harness.scheduler.scheduleWithFixedDelay(
                    definition("identity"),
                    Duration.ZERO,
                    Duration.ofNanos(10L),
                    context -> {
                        taskIds.add(context.taskId());
                        executionIds.add(context.executionId());
                        traceIds.add(context.traceId());
                        return CompletableFuture.completedFuture(null);
                    });
            harness.runRegistrationAndNextTimer();
            harness.workers.runAll();
            assertTrue(harness.timer.runNext());
            harness.workers.runAll();

            assertEquals(List.of(handle.taskId(), handle.taskId()), taskIds);
            assertEquals(2, executionIds.size());
            assertEquals(2, traceIds.size());
        } finally {
            harness.stop();
        }
    }

    /** 零延迟回调先重排、初始 schedule 后返回时，不得用旧句柄取消新节拍。 */
    @Test
    void earlyTimerCallbackKeepsTheRearmedFutureAndCancellationOwnsIt() {
        Harness harness = Harness.start(4, 2);
        CompletableFuture<Void> response = new CompletableFuture<>();
        try {
            harness.timer.beforeReturn = () -> {
                assertTrue(harness.timer.runNext());
                harness.workers.runAll();
            };
            ScheduledTaskHandle handle = harness.scheduler.scheduleAtFixedRate(
                    definition("early-timer"), Duration.ZERO, Duration.ofMillis(10), context -> response);
            assertEquals(1, harness.timer.pendingCount());
            assertEquals(CLOCK.instant().plusMillis(10), handle.snapshot().nextScheduledAt().orElseThrow());
            harness.nanoTime.set(TimeUnit.MILLISECONDS.toNanos(10));
            assertTrue(harness.timer.runNext());
            assertEquals(1, handle.snapshot().skippedRunningCount());
            assertTrue(handle.cancel());
            assertEquals(0, harness.timer.pendingCount());
            assertTrue(handle.snapshot().nextScheduledAt().isEmpty());
            response.complete(null);
            harness.workers.runAll();
            assertSame(ScheduledTaskState.CANCELLED, handle.snapshot().state());
        } finally {
            response.complete(null);
            harness.stop();
        }
    }

    /** 取消早于 schedule 返回时，迟到 future 仍应取消，且不重新发布下一次触发时间。 */
    @Test
    void cancellationBeforeFutureAttachmentDoesNotLeaveAnArmedTimer() {
        Harness harness = Harness.start(4, 2);
        try {
            harness.timer.beforeReturn = harness.scheduler::stop;
            ScheduledTaskHandle handle = harness.scheduler.scheduleOnce(
                    definition("cancel-before-attach"), Duration.ZERO,
                    context -> CompletableFuture.completedFuture(null));
            assertSame(ScheduledTaskState.CANCELLED, handle.snapshot().state());
            assertTrue(handle.snapshot().nextScheduledAt().isEmpty());
            assertEquals(0, harness.timer.pendingCount());
            assertFalse(harness.timer.runNext());
        } finally {
            harness.stop();
        }
    }

    private static ScheduledTaskHandle runOnce(
            final Harness harness,
            final String name,
            final group.zn.zero.core.scheduler.ScheduledTask task) {
        ScheduledTaskHandle handle = harness.scheduler.scheduleOnce(definition(name), Duration.ZERO, task);
        harness.runRegistrationAndNextTimer();
        harness.workers.runAll();
        return handle;
    }

    private static ScheduledTaskDefinition definition(final String name) {
        return ScheduledTaskDefinition.defaults(name);
    }

    private static void assertSnapshot(
            final ScheduledTaskSnapshot snapshot,
            final ScheduledTaskState state,
            final long executions,
            final long successes,
            final long failures) {
        assertSame(state, snapshot.state());
        assertEquals(executions, snapshot.executionCount());
        assertEquals(successes, snapshot.successCount());
        assertEquals(failures, snapshot.failureCount());
    }

    /**
     * 手动运行时组合。
     *
     * @author zn
     */
    private static final class Harness {

        /**
         * 受控单调时间。
         */
        private final AtomicLong nanoTime = new AtomicLong();

        /**
         * 手动 timer。
         */
        private final ManualTimer timer = new ManualTimer();

        /**
         * 手动后台执行器。
         */
        private final ManualExecutor workers;

        /**
         * 已观测事件。
         */
        private final List<ScheduledTaskEvent> events = new ArrayList<>();

        /**
         * 被测 scheduler。
         */
        private final LocalManagedScheduler scheduler;

        private Harness(
                final int maxTasks,
                final int maxInFlight,
                final Duration stopTimeout,
                final ManualExecutor workers,
                final ScheduledTaskObserver observer,
                final Supplier<String> idSupplier) {
            this.workers = workers;
            ScheduledTaskObserver recording = event -> {
                events.add(event);
                observer.onEvent(event);
            };
            scheduler = new LocalManagedScheduler(
                    workers,
                    recording,
                    new LocalManagedSchedulerOptions(maxTasks, maxInFlight, stopTimeout, "manual-scheduler"),
                    CLOCK,
                    nanoTime::get,
                    idSupplier,
                    options -> timer);
        }

        private static Harness start(final int maxTasks, final int maxInFlight) {
            return start(maxTasks, maxInFlight, Duration.ofMillis(100L));
        }

        private static Harness start(
                final int maxTasks,
                final int maxInFlight,
                final Duration stopTimeout) {
            return start(
                    maxTasks,
                    maxInFlight,
                    stopTimeout,
                    new ManualExecutor(),
                    ScheduledTaskObserver.noOp(),
                    ids());
        }

        private static Harness start(
                final int maxTasks,
                final int maxInFlight,
                final ManualExecutor workers,
                final ScheduledTaskObserver observer,
                final Supplier<String> idSupplier) {
            return start(
                    maxTasks,
                    maxInFlight,
                    Duration.ofMillis(100L),
                    workers,
                    observer,
                    idSupplier);
        }

        private static Harness start(
                final int maxTasks,
                final int maxInFlight,
                final Duration stopTimeout,
                final ManualExecutor workers,
                final ScheduledTaskObserver observer,
                final Supplier<String> idSupplier) {
            Harness harness = new Harness(
                    maxTasks,
                    maxInFlight,
                    stopTimeout,
                    workers,
                    observer,
                    idSupplier);
            harness.scheduler.start();
            return harness;
        }

        private static Supplier<String> ids() {
            AtomicLong sequence = new AtomicLong();
            return () -> "id-" + sequence.incrementAndGet();
        }

        private void runRegistrationAndNextTimer() {
            workers.runAll();
            assertTrue(timer.runNext());
        }

        private List<ScheduledTaskEvent> eventsOf(final ScheduledTaskEventType eventType) {
            return events.stream().filter(event -> event.eventType() == eventType).toList();
        }

        private void stop() {
            if (scheduler.state() == LifecycleState.RUNNING) {
                scheduler.stop();
            }
            workers.runAll();
        }
    }

    /**
     * 仅在测试显式推进时执行队列任务的受控 executor。
     *
     * @author zn
     */
    private static final class ManualExecutor implements Executor {

        /**
         * 待运行任务。
         */
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        /**
         * 是否主动拒绝新任务。
         */
        private boolean reject;

        @Override
        public synchronized void execute(final Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("manual rejection");
            }
            tasks.add(command);
        }

        private boolean runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.poll();
            }
            if (task == null) {
                return false;
            }
            task.run();
            return true;
        }

        private void runAll() {
            int count = 0;
            while (runNext()) {
                count++;
                if (count > 1_000) {
                    throw new AssertionError("manual executor did not become idle");
                }
            }
        }
    }

    /**
     * 保存一次性 timer 且不创建真实线程的受控 ScheduledThreadPoolExecutor。
     *
     * @author zn
     */
    private static final class ManualTimer extends ScheduledThreadPoolExecutor {

        /**
         * 待触发 timer。
         */
        private final Queue<ManualScheduledFuture> timers = new ArrayDeque<>();

        /** 仅一次：模拟 timer 在线程调度返回 future 之前已经触发。 */
        private Runnable beforeReturn;

        private ManualTimer() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(
                final Runnable command,
                final long delay,
                final TimeUnit unit) {
            if (isShutdown()) {
                throw new RejectedExecutionException("manual timer is stopped");
            }
            ManualScheduledFuture future = new ManualScheduledFuture(command, unit.toNanos(delay));
            timers.add(future);
            Runnable callback = beforeReturn;
            beforeReturn = null;
            if (callback != null) callback.run();
            return future;
        }

        private boolean runNext() {
            ManualScheduledFuture future;
            synchronized (this) {
                do {
                    future = timers.poll();
                } while (future != null && future.isCancelled());
            }
            if (future == null) {
                return false;
            }
            future.run();
            return true;
        }

        private synchronized int pendingCount() {
            return (int) timers.stream().filter(future -> !future.isCancelled()).count();
        }
    }

    /**
     * 手动一次性 timer future。
     *
     * @author zn
     */
    private static final class ManualScheduledFuture extends FutureTask<Void>
            implements RunnableScheduledFuture<Void> {

        /**
         * 注册时延迟纳秒，仅用于 ScheduledFuture 排序契约。
         */
        private final long delayNanos;

        private ManualScheduledFuture(final Runnable command, final long delayNanos) {
            super(command, null);
            this.delayNanos = delayNanos;
        }

        @Override
        public boolean isPeriodic() {
            return false;
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(final java.util.concurrent.Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    /**
     * 完成回调内联执行后故意从 whenComplete 抛异常的恶意 stage。
     *
     * @author zn
     */
    private static final class CallbackThenThrowFuture extends CompletableFuture<Void> {

        @Override
        public CompletableFuture<Void> whenComplete(
                final BiConsumer<? super Void, ? super Throwable> action) {
            super.whenComplete(action);
            throw new IllegalStateException("whenComplete failed after callback");
        }
    }

    /**
     * Error 路径测试专用异常，不模拟 JVM 资源耗尽。
     *
     * @author zn
     */
    private static final class TestFatalError extends Error {

        /**
         * 序列化版本。
         */
        private static final long serialVersionUID = 1L;
    }
}
