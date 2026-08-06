package group.zn.zero.core.scheduler;

/**
 * 受管任务对外状态。
 *
 * @author zn
 */
public enum ScheduledTaskState {

    /**
     * 已注册并等待触发，内部可能已经提交但尚未进入用户代码。
     */
    SCHEDULED,

    /**
     * 用户任务已经开始或其异步完成信号尚未结束。
     */
    RUNNING,

    /**
     * 已由调用方或运行时取消。
     */
    CANCELLED,

    /**
     * 一次性任务已经成功完成。
     */
    COMPLETED,

    /**
     * 任务失败并依据策略终止未来调度。
     */
    FAILED,

    /**
     * 一次性任务在到期提交时被容量或执行器拒绝。
     */
    REJECTED
}
