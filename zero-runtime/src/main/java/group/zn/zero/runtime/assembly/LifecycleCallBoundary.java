package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 框架统一管理的冷路径虚拟线程边界；每 runtime 最多一个实际未完成调用。
 * 超时只中断并隔离，真实完成前禁止新调用，避免迟到 start 与 stop/close 并发操作资源。
 * @author zn
 */
final class LifecycleCallBoundary {
    /** 操作系统调度精度下的最小等待粒度。 */
    private static final long MINIMUM_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
    /** 标记当前虚拟线程正在执行受隔离的用户 lifecycle 回调。 */
    private final ThreadLocal<Boolean> inCall = ThreadLocal.withInitial(() -> false);
    /** 实际完成信号，不对调用方暴露取消。 */
    private CompletableFuture<?> active;

    synchronized boolean pending() { return active != null && !active.isDone(); }

    /**
     * 判断当前线程是否正在执行本 runtime 隔离的用户回调。
     *
     * <p>重入的 stop/close 不能等待持有方的操作锁，否则会形成自等待。</p>
     */
    boolean inCall() { return inCall.get(); }

    <T> T call(final Callable<T> action, final RuntimeDeadline deadline,
            final ComponentId component, final RuntimeFailurePhase phase) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread worker;
        synchronized (this) {
            if (pending() || deadline.expired()) throw timeout(component, phase);
            active = result;
            worker = Thread.ofVirtual().name("zero-runtime-lifecycle").unstarted(() -> {
                inCall.set(true);
                try { result.complete(action.call()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
                finally { inCall.remove(); }
            });
            try { worker.start(); }
            catch (RuntimeException | Error failure) { result.completeExceptionally(failure); throw failure; }
        }
        try {
            long remainingNanos = deadline.remaining().toNanos();
            T value = result.get(Math.max(remainingNanos, MINIMUM_WAIT_NANOS), TimeUnit.NANOSECONDS);
            return value;
        } catch (TimeoutException failure) {
            worker.interrupt();
            throw timeout(component, phase);
        } catch (InterruptedException failure) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw timeout(component, phase);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("lifecycle call failed", failure.getCause());
        }
    }

    RuntimeAssemblyException timeout(final ComponentId component, final RuntimeFailurePhase phase) {
        return RuntimeAssemblyException.failure(
                phase == RuntimeFailurePhase.START || phase == RuntimeFailurePhase.HEALTH
                        ? RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT : RuntimeErrorCode.RUNTIME_SHUTDOWN_TIMEOUT,
                phase, component, "runtime=deadline-or-isolated-call");
    }
}
