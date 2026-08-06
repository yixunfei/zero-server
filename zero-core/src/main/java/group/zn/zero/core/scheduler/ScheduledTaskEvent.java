package group.zn.zero.core.scheduler;

import group.zn.zero.core.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 受管定时任务结构化观测事件。
 *
 * @param observedAt 事件观测时间。
 * @param eventType 事件类型。
 * @param scheduleType 调度类型。
 * @param taskName 稳定逻辑任务名。
 * @param taskId 注册 handle 稳定标识。
 * @param executionId 实际执行标识；没有实际业务执行时为空字符串。
 * @param traceId 业务执行或独立观测 traceId。
 * @param runSequence 实际执行序号；没有实际执行时为 0。
 * @param executionTiming 实际执行的计划与开始时间；没有实际业务执行时为空。
 * @param occurrenceCount 本事件聚合的 occurrence 数量，普通事件为 1。
 * @param elapsed 实际执行耗时；没有实际执行时为零。
 * @param errorCode 事件绑定错误码；成功事件使用系统 OK。
 * @param reason 固定低基数原因；没有附加原因时为空字符串。
 * @author zn
 */
public record ScheduledTaskEvent(
        Instant observedAt,
        ScheduledTaskEventType eventType,
        ScheduleType scheduleType,
        String taskName,
        String taskId,
        String executionId,
        String traceId,
        long runSequence,
        Optional<ScheduledTaskExecutionTiming> executionTiming,
        long occurrenceCount,
        Duration elapsed,
        ErrorCode errorCode,
        String reason) {

    /**
     * 标准化任务观测事件。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当计数、耗时或执行字段组合非法时抛出。
     */
    public ScheduledTaskEvent {
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        eventType = Objects.requireNonNull(eventType, "eventType");
        scheduleType = Objects.requireNonNull(scheduleType, "scheduleType");
        taskName = requireText(taskName, "taskName");
        taskId = requireText(taskId, "taskId");
        executionId = Objects.requireNonNull(executionId, "executionId");
        traceId = requireText(traceId, "traceId");
        executionTiming = Objects.requireNonNull(executionTiming, "executionTiming");
        elapsed = Objects.requireNonNull(elapsed, "elapsed");
        errorCode = Objects.requireNonNull(errorCode, "errorCode");
        reason = Objects.requireNonNull(reason, "reason");
        if (runSequence < 0 || occurrenceCount <= 0 || elapsed.isNegative()) {
            throw new IllegalArgumentException("counts and elapsed are invalid");
        }
        if (executionId.isBlank() != (runSequence == 0)) {
            throw new IllegalArgumentException("executionId and runSequence must both describe an execution");
        }
        if (executionTiming.isPresent() != (runSequence > 0)) {
            throw new IllegalArgumentException("executionTiming must only describe an execution");
        }
    }

    /**
     * 返回事件是否关联一次实际业务执行。
     *
     * @return true 表示具有 executionId 和正数执行序号；线程安全。
     */
    public boolean hasExecution() {
        return runSequence > 0;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
