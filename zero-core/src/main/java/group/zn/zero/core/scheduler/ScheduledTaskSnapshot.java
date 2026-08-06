package group.zn.zero.core.scheduler;

import group.zn.zero.core.error.ErrorCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 受管任务不可变状态快照。
 *
 * @param taskId 注册 handle 稳定标识。
 * @param taskName 稳定逻辑任务名。
 * @param scheduleType 调度类型。
 * @param state 当前对外状态。
 * @param executionCount 实际开始执行次数。
 * @param successCount 成功完成次数。
 * @param failureCount 失败次数。
 * @param skippedRunningCount 因仍在执行而跳过的 occurrence 数量。
 * @param skippedCapacityCount 因全局容量不足而跳过的 occurrence 数量。
 * @param skippedLateCount 因 timer 迟到而跳过的 occurrence 数量。
 * @param rejectionCount 一次性或底层执行器拒绝次数。
 * @param nextScheduledAt 下一次计划时间；无未来触发时为空。
 * @param lastCompletedAt 最近一次执行完成时间；尚未完成时为空。
 * @param lastErrorCode 最近错误码；尚无错误时为空。
 * @author zn
 */
public record ScheduledTaskSnapshot(
        String taskId,
        String taskName,
        ScheduleType scheduleType,
        ScheduledTaskState state,
        long executionCount,
        long successCount,
        long failureCount,
        long skippedRunningCount,
        long skippedCapacityCount,
        long skippedLateCount,
        long rejectionCount,
        Optional<Instant> nextScheduledAt,
        Optional<Instant> lastCompletedAt,
        Optional<ErrorCode> lastErrorCode) {

    /**
     * 标准化任务快照。
     *
     * @throws NullPointerException 当必要字段或 Optional 为空时抛出。
     * @throws IllegalArgumentException 当任意计数为负数时抛出。
     */
    public ScheduledTaskSnapshot {
        taskId = requireText(taskId, "taskId");
        taskName = requireText(taskName, "taskName");
        scheduleType = Objects.requireNonNull(scheduleType, "scheduleType");
        state = Objects.requireNonNull(state, "state");
        nextScheduledAt = Objects.requireNonNull(nextScheduledAt, "nextScheduledAt");
        lastCompletedAt = Objects.requireNonNull(lastCompletedAt, "lastCompletedAt");
        lastErrorCode = Objects.requireNonNull(lastErrorCode, "lastErrorCode");
        if (executionCount < 0
                || successCount < 0
                || failureCount < 0
                || skippedRunningCount < 0
                || skippedCapacityCount < 0
                || skippedLateCount < 0
                || rejectionCount < 0) {
            throw new IllegalArgumentException("snapshot counts must not be negative");
        }
    }

    /**
     * 返回任务是否仍属于活动状态。
     *
     * @return SCHEDULED 或 RUNNING 时返回 true；线程安全。
     */
    public boolean active() {
        return state == ScheduledTaskState.SCHEDULED || state == ScheduledTaskState.RUNNING;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
