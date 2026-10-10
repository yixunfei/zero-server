package group.zn.zero.runtime.bootstrap;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** 单次执行器关闭的累计预算及故障聚合；仅由关闭线程访问。 @author zn */
final class ExecutorShutdown {
    /** 单调起点。 */
    private final long started = System.nanoTime();
    /** 总等待纳秒。 */
    private final long timeout;
    /** 原始关闭故障的安全包装。 */
    private ZeroException failure;
    /** 等待中曾被中断，退出时恢复。 */
    private boolean interrupted;

    ExecutorShutdown(final Duration timeout) { this.timeout = timeout.toNanos(); }

    /** 两阶段关闭；timer 等待结束或预算耗尽并请求中断后，再关闭 IO 准入。 */
    void close(final List<ExecutorService> timers, final List<ExecutorService> workers) {
        stop(timers);
        stop(workers);
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }

    private void stop(final List<ExecutorService> services) {
        for (ExecutorService service : services) {
            try { service.shutdown(); }
            catch (RuntimeException | Error cause) { record(cause); }
        }
        for (ExecutorService service : services) {
            boolean terminated = false;
            if (!interrupted) {
                try { terminated = service.awaitTermination(remaining(), TimeUnit.NANOSECONDS); }
                catch (InterruptedException cause) { interrupted = true; record(cause); }
                catch (RuntimeException | Error cause) { record(cause); }
            }
            if (!terminated) {
                try { service.shutdownNow(); }
                catch (RuntimeException | Error cause) { record(cause); }
            }
        }
    }

    private long remaining() { return Math.max(0L, timeout - Math.max(0L, System.nanoTime() - started)); }

    private void record(final Throwable cause) {
        if (failure == null) failure = ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "runtime executor close failed", cause);
        else if (failure != cause) failure.addSuppressed(cause);
    }
}
