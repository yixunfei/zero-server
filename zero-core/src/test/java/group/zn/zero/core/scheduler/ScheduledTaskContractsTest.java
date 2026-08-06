package group.zn.zero.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 受管定时任务 core 值对象、便捷适配器和不变量测试。
 *
 * @author zn
 */
class ScheduledTaskContractsTest {

    /**
     * 验证默认任务定义使用失败取消策略并标准化任务名。
     */
    @Test
    void shouldCreateDefaultDefinition() {
        ScheduledTaskDefinition definition = ScheduledTaskDefinition.defaults("  world-tick  ");

        assertEquals("world-tick", definition.taskName());
        assertSame(ScheduledTaskFailurePolicy.CANCEL_ON_FAILURE, definition.failurePolicy());
        assertThrows(IllegalArgumentException.class, () -> ScheduledTaskDefinition.defaults("bad\nname"));
        assertThrows(IllegalArgumentException.class, () -> ScheduledTaskDefinition.defaults(" "));
    }

    /**
     * 验证同步 Runnable 与 Consumer 会在调用线程完成并返回非空完成信号。
     */
    @Test
    void shouldAdaptSynchronousActions() {
        ScheduledTaskContext context = context();
        AtomicBoolean runnableCalled = new AtomicBoolean();
        AtomicReference<ScheduledTaskContext> received = new AtomicReference<>();

        ScheduledTasks.runnable(() -> runnableCalled.set(true))
                .execute(context)
                .toCompletableFuture()
                .join();
        ScheduledTasks.consumer(received::set)
                .execute(context)
                .toCompletableFuture()
                .join();

        assertTrue(runnableCalled.get());
        assertSame(context, received.get());
        assertThrows(NullPointerException.class, () -> ScheduledTasks.runnable(null));
        assertThrows(NullPointerException.class, () -> ScheduledTasks.consumer(null));
    }

    /**
     * 验证观测事件不会为控制事件伪造执行标识，并拒绝字段组合不一致。
     */
    @Test
    void shouldValidateExecutionEventIdentity() {
        ScheduledTaskEvent controlEvent = event("", 0L);
        ScheduledTaskEvent executionEvent = event("execution-1", 1L);

        assertFalse(controlEvent.hasExecution());
        assertTrue(executionEvent.hasExecution());
        assertThrows(IllegalArgumentException.class, () -> event("execution-1", 0L));
        assertThrows(IllegalArgumentException.class, () -> event("", 1L));
    }

    /**
     * 验证快照活动判断和非负计数约束。
     */
    @Test
    void shouldValidateSnapshotStateAndCounts() {
        ScheduledTaskSnapshot active = snapshot(ScheduledTaskState.SCHEDULED, 0L);
        ScheduledTaskSnapshot terminal = snapshot(ScheduledTaskState.COMPLETED, 1L);

        assertTrue(active.active());
        assertFalse(terminal.active());
        assertThrows(IllegalArgumentException.class, () -> snapshot(ScheduledTaskState.FAILED, -1L));
    }

    private static ScheduledTaskContext context() {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        return new ScheduledTaskContext(
                "world-tick",
                "task-1",
                "execution-1",
                "trace-1",
                1L,
                now,
                now);
    }

    private static ScheduledTaskEvent event(final String executionId, final long runSequence) {
        return new ScheduledTaskEvent(
                Instant.parse("2026-07-27T00:00:00Z"),
                ScheduledTaskEventType.SUCCEEDED,
                ScheduleType.ONCE,
                "world-tick",
                "task-1",
                executionId,
                "trace-1",
                runSequence,
                runSequence == 0L
                        ? Optional.empty()
                        : Optional.of(new ScheduledTaskExecutionTiming(
                                Instant.parse("2026-07-27T00:00:00Z"),
                                Instant.parse("2026-07-27T00:00:00Z"))),
                1L,
                Duration.ZERO,
                SystemErrorCode.OK,
                "");
    }

    private static ScheduledTaskSnapshot snapshot(
            final ScheduledTaskState state,
            final long executionCount) {
        return new ScheduledTaskSnapshot(
                "task-1",
                "world-tick",
                ScheduleType.ONCE,
                state,
                executionCount,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
