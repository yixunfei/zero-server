package group.zn.zero.core.scheduler;

/**
 * 受管任务调度类型。
 *
 * @author zn
 */
public enum ScheduleType {

    /**
     * 一次性延迟任务。
     */
    ONCE,

    /**
     * 从前一次异步执行完成后计算下一次延迟的周期任务。
     */
    FIXED_DELAY,

    /**
     * 按单调时间节拍触发且错过节拍不追赶的周期任务。
     */
    FIXED_RATE
}
