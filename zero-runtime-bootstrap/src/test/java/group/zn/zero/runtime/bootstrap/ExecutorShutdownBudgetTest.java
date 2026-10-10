package group.zn.zero.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 执行器共享关闭预算、中断恢复及失败聚合测试。 @author zn */
class ExecutorShutdownBudgetTest {
    @Test void waitingBudgetIsSharedAcrossTimerAndWorkers() {
        var timer = new Probe(); timer.consume = true;
        var worker = new Probe();
        new ExecutorShutdown(Duration.ofMillis(20)).close(List.of(timer), List.of(worker));
        assertTrue(timer.waitNanos <= TimeUnit.MILLISECONDS.toNanos(20));
        assertEquals(0, worker.waitNanos);
        assertTrue(timer.forced && worker.forced);
    }
    @Test void interruptionRestoresFlagAndStillShutsEveryResource() {
        var timer = new Probe(); timer.interrupt = true;
        var worker = new Probe();
        try {
            assertThrows(ZeroException.class, () -> new ExecutorShutdown(Duration.ofSeconds(1))
                    .close(List.of(timer), List.of(worker)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(timer.forced && worker.forced && worker.shutdown);
        } finally { Thread.interrupted(); }
    }
    @Test void failuresAreAggregatedAndDoNotSkipLaterPools() {
        var first = new Probe(); first.fail = true;
        var second = new Probe(); second.fail = true;
        var last = new Probe();
        var failure = assertThrows(ZeroException.class, () -> new ExecutorShutdown(Duration.ofMillis(20))
                .close(List.of(first), List.of(second, last)));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(first.forced && second.forced && last.forced && last.shutdown);
    }
    /** 记录关闭调用与等待预算的受控执行器。 @author zn */
    private static final class Probe extends AbstractExecutorService {
        /** 已请求正常关闭。 */
        private boolean shutdown;
        /** 已请求中断。 */
        private boolean forced;
        /** 等待消耗全部预算。 */
        private boolean consume;
        /** 等待抛出中断。 */
        private boolean interrupt;
        /** 正常关闭失败。 */
        private boolean fail;
        /** 最近等待预算。 */
        private long waitNanos = -1;
        @Override public void shutdown() { shutdown = true; if (fail) throw new IllegalStateException("shutdown"); }
        @Override public List<Runnable> shutdownNow() { forced = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            waitNanos = unit.toNanos(timeout);
            if (interrupt) throw new InterruptedException("wait");
            if (consume) new CountDownLatch(1).await(timeout, unit);
            return false;
        }
        @Override public void execute(final Runnable task) { throw new UnsupportedOperationException(); }
    }
}
