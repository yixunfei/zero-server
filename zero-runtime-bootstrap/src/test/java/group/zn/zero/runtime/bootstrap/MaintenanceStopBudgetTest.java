package group.zn.zero.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.LifecycleState;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 维护停止预算不代表物理 IO 已取消，晚到完成后可重试清理。 @author zn */
class MaintenanceStopBudgetTest {
    /** 短预算内失败但保留在途，完成后 stop 可成功。 */
    @Test void pendingMaintenanceUsesConfiguredBudgetAndCanFinishLater() throws Exception {
        var started = new CountDownLatch(1);
        var work = new CompletableFuture<Void>();
        try (var executors = ZeroRuntimeExecutors.remoteIo("stop-budget", Duration.ofMillis(20))) {
            var task = new PeriodicAsyncTask(executors, "test", Duration.ofHours(1), () -> {
                started.countDown(); return work;
            });
            task.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            try {
                CompletableFuture.runAsync(() -> assertThrows(ZeroException.class, task::stop)).get(2, TimeUnit.SECONDS);
                assertEquals(LifecycleState.FAILED, task.state());
                assertTrue(!work.isDone());
            } finally { work.complete(null); }
            task.stop();
            assertEquals(LifecycleState.STOPPED, task.state());
        }
    }

    /** 非法预算在创建执行器前失败。 */
    @Test void invalidShutdownBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ZeroRuntimeExecutors.remoteIo("invalid", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> ZeroRuntimeExecutors.localPrototype("invalid", 1, Duration.ofMillis(-1)));
    }
}
