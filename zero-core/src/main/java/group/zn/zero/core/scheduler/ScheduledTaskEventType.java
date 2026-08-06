package group.zn.zero.core.scheduler;

/**
 * 受管任务观测事件类型。
 *
 * @author zn
 */
public enum ScheduledTaskEventType {

    /**
     * 任务已经注册。
     */
    REGISTERED,

    /**
     * 用户任务开始执行。
     */
    STARTED,

    /**
     * 用户任务成功完成。
     */
    SUCCEEDED,

    /**
     * 用户任务同步抛出或异步失败。
     */
    FAILED,

    /**
     * 因同一任务仍在执行而聚合跳过。
     */
    SKIPPED_RUNNING,

    /**
     * 因全局在途容量不足而聚合跳过。
     */
    SKIPPED_CAPACITY,

    /**
     * 因 timer 迟到而聚合跳过过期节拍。
     */
    SKIPPED_LATE,

    /**
     * 一次性任务被容量或执行器拒绝。
     */
    REJECTED,

    /**
     * Scheduler 停止等待超过配置上限。
     */
    STOP_TIMEOUT,

    /**
     * 任务已经取消。
     */
    CANCELLED
}
