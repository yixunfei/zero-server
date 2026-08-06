package group.zn.zero.starter.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.scheduler.ScheduleType;
import group.zn.zero.core.scheduler.ScheduledTaskEvent;
import group.zn.zero.core.scheduler.ScheduledTaskEventType;
import group.zn.zero.core.scheduler.ScheduledTaskExecutionTiming;
import group.zn.zero.core.scheduler.SchedulerErrorCode;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogType;
import group.zn.zero.monitor.InMemoryMetricRegistry;
import group.zn.zero.monitor.MetricSample;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 受管定时任务标准日志、ErrorCode 和低基数指标 focused tests。
 *
 * @author zn
 */
class LoggingScheduledTaskObserverTest {

    /**
     * 验证完成、聚合跳过和拒绝事件会写入结构化日志与对应指标。
     */
    @Test
    void shouldWriteStructuredLogsAndMetrics() {
        InMemoryLogSink logSink = new InMemoryLogSink();
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        LoggingScheduledTaskObserver observer = new LoggingScheduledTaskObserver(
                new LogPipeline(List.of(), logSink),
                registry);

        observer.onEvent(event(ScheduledTaskEventType.SUCCEEDED, "execution-1", 1L, 1L, "", SystemErrorCode.OK));
        observer.onEvent(event(ScheduledTaskEventType.SKIPPED_LATE, "", 0L, 7L, "late", SystemErrorCode.OK));
        observer.onEvent(event(
                ScheduledTaskEventType.REJECTED,
                "",
                0L,
                1L,
                "executor",
                SchedulerErrorCode.EXECUTOR_REJECTED));

        assertEquals(3, logSink.records().size());
        assertNull(logSink.records().getFirst().errorCode());
        assertNull(logSink.records().get(1).errorCode());
        assertSame(LogType.ERROR, logSink.records().get(2).logType());
        assertSame(SchedulerErrorCode.EXECUTOR_REJECTED, logSink.records().get(2).errorCode());
        assertEquals("safe-task", logSink.records().getFirst().fields().get("taskName"));
        assertEquals("1", logSink.records().getFirst().fields().get("delayMillis"));
        assertTrue(logSink.records().getFirst().fields().containsKey("completedAt"));
        assertEquals("7", logSink.records().get(1).fields().get("occurrenceCount"));

        List<MetricSample> samples = registry.samples();
        assertEquals(4, samples.size());
        assertEquals(7D, sample(samples, LoggingScheduledTaskObserver.SKIPPED_TOTAL).value());
        assertTrue(samples.stream().allMatch(sample -> allowedLabels().containsAll(sample.labels().keySet())));
        assertTrue(samples.stream().allMatch(sample -> sample.labels().keySet().stream()
                .noneMatch(Set.of("taskName", "taskId", "executionId", "traceId")::contains)));
    }

    /**
     * 验证重复创建 observer 不会重复注册同名指标定义。
     */
    @Test
    void shouldRegisterMetricDefinitionsOnce() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        InMemoryLogSink logSink = new InMemoryLogSink();

        LogPipeline logPipeline = new LogPipeline(List.of(), logSink);
        new LoggingScheduledTaskObserver(logPipeline, registry);
        new LoggingScheduledTaskObserver(logPipeline, registry);

        assertEquals(4, registry.definitions().size());
        assertEquals(4, registry.definitions().stream().map(definition -> definition.name()).distinct().count());
    }

    /**
     * 验证无业务执行的事件不伪造 executionId，但拥有独立 observation traceId。
     */
    @Test
    void shouldKeepControlObservationIdentitySeparate() {
        ScheduledTaskEvent skipped = event(
                ScheduledTaskEventType.SKIPPED_CAPACITY,
                "",
                0L,
                2L,
                "capacity",
                SystemErrorCode.OK);

        assertFalse(skipped.hasExecution());
        assertTrue(skipped.executionId().isEmpty());
        assertFalse(skipped.traceId().isBlank());
    }

    private static MetricSample sample(final List<MetricSample> samples, final String name) {
        return samples.stream().filter(sample -> name.equals(sample.name())).findFirst().orElseThrow();
    }

    private static Set<String> allowedLabels() {
        return Set.of("scheduleType", "result", "reason");
    }

    private static ScheduledTaskEvent event(
            final ScheduledTaskEventType eventType,
            final String executionId,
            final long runSequence,
            final long occurrenceCount,
            final String reason,
            final group.zn.zero.core.error.ErrorCode errorCode) {
        return new ScheduledTaskEvent(
                Instant.parse("2026-07-27T00:00:00Z"),
                eventType,
                ScheduleType.FIXED_RATE,
                "safe-task",
                "task-1",
                executionId,
                executionId.isEmpty() ? "observation-trace" : "execution-trace",
                runSequence,
                executionId.isEmpty()
                        ? Optional.empty()
                        : Optional.of(new ScheduledTaskExecutionTiming(
                                Instant.parse("2026-07-27T00:00:00Z"),
                                Instant.parse("2026-07-27T00:00:00.001Z"))),
                occurrenceCount,
                Duration.ofMillis(executionId.isEmpty() ? 0L : 5L),
                errorCode,
                reason);
    }
}
