package group.zn.zero.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 生命周期相反过渡不能等待正在执行的用户代码。 @author zn */
class LifecycleTransitionBoundaryTest {
    /** STARTING 的 stop 必须在启动回调释放前明确拒绝。 */
    @Test void stopRejectsWhileStartCallbackIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lifecycle = new AbstractLifecycle() {
            @Override protected void doStart() { entered.countDown(); await(release); }
        };
        var starting = CompletableFuture.runAsync(lifecycle::start);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            CompletableFuture.runAsync(() -> assertThrows(ZeroException.class, lifecycle::stop))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(LifecycleState.STARTING, lifecycle.state());
        } finally { release.countDown(); starting.get(2, TimeUnit.SECONDS); }
        lifecycle.stop();
        assertEquals(LifecycleState.STOPPED, lifecycle.state());
    }

    /** STOPPING 的 start 同样拒绝，查询与同向重复调用无需等待回调。 */
    @Test void startRejectsWhileStopCallbackIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lifecycle = new AbstractLifecycle() {
            @Override protected void doStop() { entered.countDown(); await(release); }
        };
        lifecycle.start();
        var stopping = CompletableFuture.runAsync(lifecycle::stop);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            CompletableFuture.runAsync(() -> {
                assertThrows(ZeroException.class, lifecycle::start);
                lifecycle.stop();
            }).get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); stopping.get(2, TimeUnit.SECONDS); }
        assertEquals(LifecycleState.STOPPED, lifecycle.state());
    }

    private static void await(final CountDownLatch signal) {
        try { assertTrue(signal.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
