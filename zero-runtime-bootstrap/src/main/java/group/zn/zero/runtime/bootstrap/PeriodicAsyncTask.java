package group.zn.zero.runtime.bootstrap;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 运行时异步维护任务；单次在途、跳过重叠 tick，停止先取消计时再等待 IO。 @author zn */
public final class PeriodicAsyncTask extends AbstractLifecycle {
    /** 运行时资源所有者。 */
    private final ZeroRuntimeExecutors executors;
    /** 稳定任务职责名。 */
    private final String name;
    /** Tick 间隔。 */
    private final Duration interval;
    /** IO 域任务。 */
    private final Supplier<? extends CompletionStage<?>> action;
    /** 不与生命周期监视器混用的运行锁。 */
    private final Object guard = new Object();
    /** 停止标记。 */
    private boolean stopping = true;
    /** 最近一次任务完成屏障。 */
    private CompletableFuture<Void> running = CompletableFuture.completedFuture(null);
    /** 可取消的 timer 句柄。 */
    private ScheduledFuture<?> handle;
    /** 可观测失败计数。 */
    private final AtomicLong failureCount = new AtomicLong();
    /** 最近失败；只保留一条，不向日志输出外部凭据。 */
    private volatile Throwable lastFailure;

    /** @param executors 受管执行器。 @param name 稳定职责名。 @param interval 正间隔。
     * @param action 异步动作；只在受管 IO 域调用。线程安全，不在构造期间启动。 */
    public PeriodicAsyncTask(final ZeroRuntimeExecutors executors, final String name, final Duration interval,
            final Supplier<? extends CompletionStage<?>> action) {
        this.executors = Objects.requireNonNull(executors); this.name = Objects.requireNonNull(name);
        this.interval = Objects.requireNonNull(interval); this.action = Objects.requireNonNull(action);
        if (name.isBlank() || interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("invalid periodic task");
        interval.toNanos();
    }

    /** @return 累计失败次数；线程安全。 */
    public long failureCount() { return failureCount.get(); }
    /** @return 最近失败或 null；线程安全，返回值可能包含外部信息，只供诊断使用。 */
    public Throwable lastFailure() { return lastFailure; }

    /** 启动时拒绝内联远程执行域，避免维护任务占用 timer/Actor。 */
    @Override protected void doStart() {
        if (executors.remoteIoMayInline()) throw new IllegalStateException("maintenance requires non-inline remote IO executor");
        synchronized (guard) {
            stopping = false;
            handle = executors.scheduledExecutor(name).scheduleWithFixedDelay(this::tick, 0,
                    interval.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** 取消准入后等待在途任务，超时/中断明确失败；仅管理线程调用。 */
    @Override protected void doStop() {
        CompletableFuture<Void> pending;
        synchronized (guard) {
            stopping = true;
            if (handle != null) handle.cancel(false);
            pending = running;
        }
        try { pending.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "maintenance stop interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "maintenance stop failed", failure);
        }
    }

    private void tick() {
        var completion = new CompletableFuture<Void>();
        synchronized (guard) {
            if (stopping || !running.isDone()) return;
            running = completion;
        }
        try {
            executors.remoteIoExecutor().execute(() -> {
                try { action.get().whenComplete((ignored, failure) -> complete(completion, failure)); }
                catch (RuntimeException | Error failure) { complete(completion, failure); }
            });
        } catch (RuntimeException failure) { complete(completion, failure); }
    }

    private void complete(final CompletableFuture<Void> completion, final Throwable failure) {
        if (failure != null) {
            lastFailure = failure; failureCount.incrementAndGet();
            System.getLogger(PeriodicAsyncTask.class.getName()).log(System.Logger.Level.ERROR,
                    SystemErrorCode.SYSTEM_ERROR.code() + ": periodic maintenance failed: " + name);
        }
        completion.complete(null);
    }
}
