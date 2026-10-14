package group.zn.zero.runtime.data;

import group.zn.zero.data.persistence.PersistenceScheduleHandle;
import group.zn.zero.data.persistence.PersistenceScheduler;
import group.zn.zero.runtime.bootstrap.PeriodicAsyncTask;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 持久化周期任务的受管 IO 适配；复用单次在途与完成感知，不自行创建线程池。 @author zn */
public final class ManagedPersistenceScheduler implements PersistenceScheduler {
    /** 外部拥有的框架执行器，必须比任务句柄晚关闭。 */
    private final ZeroRuntimeExecutors executors;

    /** @param executors 框架执行器，不可为空；构造无资源副作用。 */
    public ManagedPersistenceScheduler(final ZeroRuntimeExecutors executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /** 注册并启动异步维护；线程安全，调用方持有并取消返回句柄。
     * @param taskName 任务名称。 @param interval 正间隔。 @param task 异步动作。
     * @return 可观测句柄；取消阻止后续准入，并在框架关闭预算内等待已准入任务。
     * @throws group.zn.zero.core.error.ZeroException 执行域内联或启动失败。
     * @throws IllegalArgumentException 间隔或名称非法。 */
    @Override public PersistenceScheduleHandle scheduleAtFixedRate(final String taskName, final Duration interval,
            final Supplier<? extends CompletionStage<?>> task) {
        var periodic = new PeriodicAsyncTask(executors, taskName, interval, task);
        periodic.start();
        return new Handle(periodic);
    }

    /** 由持久化管理器拥有的任务句柄；取消不关闭共享执行器。 @author zn */
    private static final class Handle implements PersistenceScheduleHandle {
        /** 受管异步任务。 */
        private final PeriodicAsyncTask task;
        /** 取消状态，取消等待失败后可再次 stop。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Handle(final PeriodicAsyncTask task) { this.task = task; }
        /** 停止并等待当前动作；管理线程调用，失败明确传播。 */
        @Override public void cancel() { cancelled.set(true); task.stop(); }
        /** @return 取消是否已请求；线程安全。 */
        @Override public boolean cancelled() { return cancelled.get(); }
        /** @return 实际异步失败次数；线程安全。 */
        @Override public long failureCount() { return task.failureCount(); }
        /** @return 最近失败或 null；线程安全。 */
        @Override public Throwable lastFailure() { return task.lastFailure(); }
    }
}
