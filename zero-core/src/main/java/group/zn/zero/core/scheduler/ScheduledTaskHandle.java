package group.zn.zero.core.scheduler;

/**
 * 受管定时任务句柄。
 *
 * @author zn
 */
public interface ScheduledTaskHandle extends AutoCloseable {

    /**
     * 返回注册 handle 稳定标识。
     *
     * @return taskId；不可为空；线程安全。
     */
    String taskId();

    /**
     * 返回稳定逻辑任务名。
     *
     * @return 任务名；不可为空；线程安全。
     */
    String taskName();

    /**
     * 返回当前不可变状态快照。
     *
     * @return 快照；不可为空、不可变、线程安全。
     */
    ScheduledTaskSnapshot snapshot();

    /**
     * 幂等取消未来调度。
     *
     * <p>取消不会中断已经进入用户代码的任务，也不会强制取消用户返回的 CompletionStage。
     *
     * @return true 表示本次调用首次发起取消；false 表示已经取消或进入其他终态；线程安全。
     */
    boolean cancel();

    /**
     * 关闭句柄并取消未来调度。
     */
    @Override
    default void close() {
        cancel();
    }
}
