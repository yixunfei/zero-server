package group.zn.zero.core.scheduler;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 受管定时任务错误码。
 *
 * @author zn
 */
public enum SchedulerErrorCode implements ErrorCode {

    /**
     * 调度参数非法。
     */
    INVALID_ARGUMENT(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-SCHEDULER-INVALID-ARGUMENT",
            "scheduler argument is invalid"),

    /**
     * Starter 调度配置非法。
     */
    INVALID_OPTIONS(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-SCHEDULER-INVALID-OPTIONS",
            "scheduler options are invalid"),

    /**
     * 调度器未处于可接受任务的运行状态。
     */
    NOT_RUNNING(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-NOT-RUNNING",
            "scheduler is not running"),

    /**
     * 活动任务数量达到上限。
     */
    TASK_LIMIT_EXCEEDED(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-TASK-LIMIT-EXCEEDED",
            "scheduler active task limit was exceeded"),

    /**
     * Timer 资源拒绝或无法安排任务。
     */
    TIMER_REJECTED(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-TIMER-REJECTED",
            "scheduler timer rejected task"),

    /**
     * 后台执行器拒绝业务任务。
     */
    EXECUTOR_REJECTED(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-EXECUTOR-REJECTED",
            "scheduler executor rejected task"),

    /**
     * 用户任务同步执行或异步完成失败。
     */
    TASK_EXECUTION_FAILED(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-TASK-EXECUTION-FAILED",
            "scheduled task execution failed"),

    /**
     * 调度 observer 执行失败。
     */
    OBSERVER_FAILED(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-OBSERVER-FAILED",
            "scheduler observer failed"),

    /**
     * 调度器在停止超时内仍有用户任务未完成。
     */
    STOP_TIMEOUT(
            ErrorCategory.SYSTEM,
            "ZERO-SCHEDULER-STOP-TIMEOUT",
            "scheduler stop timed out");

    /**
     * 错误分类。
     */
    private final ErrorCategory category;

    /**
     * 对外稳定错误码。
     */
    private final String code;

    /**
     * 默认错误说明。
     */
    private final String message;

    SchedulerErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回对外稳定错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误说明。
     *
     * @return 默认说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
