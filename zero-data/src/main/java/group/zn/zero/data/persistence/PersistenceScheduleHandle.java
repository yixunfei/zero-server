package group.zn.zero.data.persistence;

/**
 * 持久化调度句柄。
 *
 * @author zn
 */
public interface PersistenceScheduleHandle extends AutoCloseable {

    /**
     * 取消调度。
     *
     * <p>取消后不得再触发对应 flush 任务。
     */
    void cancel();

    /**
     * 返回是否已经取消。
     *
     * @return true 表示已取消；线程安全性由实现声明。
     */
    boolean cancelled();

    /**
     * 关闭调度句柄。
     */
    @Override
    default void close() {
        cancel();
    }
}
