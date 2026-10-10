package group.zn.zero.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Timer 已经准入的动作可以在关闭窗口完成最后一次 IO 提交。 @author zn */
class ExecutorCloseOrderTest {
    /** 通过 timer.shutdown 的状态信号建立确定性交错，不依赖 sleep。 */
    @Test void timerDrainsBeforeIoAdmissionCloses() throws Exception {
        var executors = ZeroRuntimeExecutors.remoteIo("close-order");
        var timer = executors.scheduledExecutor("close-order");
        var entered = new CountDownLatch(1);
        var submitted = new CompletableFuture<Void>();
        timer.execute(() -> {
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!timer.isShutdown() && System.nanoTime() < deadline) Thread.onSpinWait();
            try {
                assertTrue(timer.isShutdown());
                executors.remoteIoExecutor().execute(() -> submitted.complete(null));
            } catch (RuntimeException | Error failure) { submitted.completeExceptionally(failure); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try { executors.close(); submitted.get(2, TimeUnit.SECONDS); }
        finally { executors.close(); }
    }
}
