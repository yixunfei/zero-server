package group.zn.zero.core.scheduler;

import java.time.Instant;
import java.util.Objects;

/**
 * 单次受管任务执行上下文。
 *
 * @param taskName 稳定逻辑任务名。
 * @param taskId 当前注册 handle 的稳定标识。
 * @param executionId 本次实际执行标识。
 * @param traceId 本次实际执行链路标识。
 * @param runSequence 当前 handle 的实际执行序号，从 1 开始。
 * @param scheduledAt 本次计划触发的观测时间。
 * @param startedAt 用户任务实际开始的观测时间。
 * @author zn
 */
public record ScheduledTaskContext(
        String taskName,
        String taskId,
        String executionId,
        String traceId,
        long runSequence,
        Instant scheduledAt,
        Instant startedAt) {

    /**
     * 标准化执行上下文。
     *
     * @throws NullPointerException 当文本或时间字段为空时抛出。
     * @throws IllegalArgumentException 当文本为空白或执行序号不是正数时抛出。
     */
    public ScheduledTaskContext {
        taskName = requireText(taskName, "taskName");
        taskId = requireText(taskId, "taskId");
        executionId = requireText(executionId, "executionId");
        traceId = requireText(traceId, "traceId");
        if (runSequence <= 0) {
            throw new IllegalArgumentException("runSequence must be positive");
        }
        scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
