package group.zn.zero.core.scheduler;

/**
 * 周期任务未处理异常后的调度策略。
 *
 * @author zn
 */
public enum ScheduledTaskFailurePolicy {

    /**
     * 发生未处理异常后取消未来调度。
     */
    CANCEL_ON_FAILURE,

    /**
     * 记录失败并在下一个完整周期后继续。
     */
    CONTINUE
}
