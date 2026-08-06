package group.zn.zero.data.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手动触发的持久化调度器。
 *
 * <p>该实现不创建线程，适用于单元测试、本地原型和由外部循环主动触发的场景。
 *
 * @author zn
 */
public final class ManualPersistenceScheduler implements PersistenceScheduler {

    /**
     * 调度任务列表。
     */
    private final List<ManualScheduleHandle> handles = new CopyOnWriteArrayList<>();

    /**
     * 创建手动触发的持久化调度器。
     */
    public ManualPersistenceScheduler() {
    }

    /**
     * 注册固定间隔任务。
     *
     * @param taskName 任务名称；不可为空。
     * @param interval 调度间隔；不可为空且必须大于 0。
     * @param task 任务逻辑；不可为空。
     * @return 调度句柄；不可为空；线程安全。
     */
    @Override
    public PersistenceScheduleHandle scheduleAtFixedRate(
            final String taskName,
            final Duration interval,
            final Runnable task) {
        ManualScheduleHandle handle = new ManualScheduleHandle(
                requireText(taskName, "taskName"),
                requirePositive(interval),
                Objects.requireNonNull(task, "task"));
        handles.add(handle);
        return handle;
    }

    /**
     * 触发全部未取消任务。
     *
     * @return 实际触发的任务数量；线程安全。
     */
    public int triggerAll() {
        int triggered = 0;
        for (ManualScheduleHandle handle : handles) {
            if (handle.trigger()) {
                triggered++;
            }
        }
        return triggered;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private static Duration requirePositive(final Duration interval) {
        Duration current = Objects.requireNonNull(interval, "interval");
        if (current.isZero() || current.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return current;
    }

    /**
     * 手动调度句柄。
     *
     * @author zn
     */
    private static final class ManualScheduleHandle implements PersistenceScheduleHandle {

        /**
         * 任务名称。
         */
        private final String taskName;

        /**
         * 调度间隔。
         */
        private final Duration interval;

        /**
         * 任务逻辑。
         */
        private final Runnable task;

        /**
         * 是否已取消。
         */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /**
         * 创建手动调度句柄。
         *
         * @param taskName 任务名称；不可为空。
         * @param interval 调度间隔；不可为空。
         * @param task 任务逻辑；不可为空。
         */
        ManualScheduleHandle(final String taskName, final Duration interval, final Runnable task) {
            this.taskName = taskName;
            this.interval = interval;
            this.task = task;
        }

        /**
         * 取消调度。
         */
        @Override
        public void cancel() {
            cancelled.set(true);
        }

        /**
         * 返回是否已经取消。
         *
         * @return true 表示已取消；线程安全。
         */
        @Override
        public boolean cancelled() {
            return cancelled.get();
        }

        /**
         * 返回任务名称。
         *
         * @return 任务名称；不可为空；线程安全。
         */
        String taskName() {
            return taskName;
        }

        /**
         * 返回调度间隔。
         *
         * @return 调度间隔；不可为空；线程安全。
         */
        Duration interval() {
            return interval;
        }

        /**
         * 手动触发任务。
         *
         * @return true 表示已触发；线程安全。
         */
        boolean trigger() {
            if (cancelled()) {
                return false;
            }
            task.run();
            return true;
        }
    }
}
