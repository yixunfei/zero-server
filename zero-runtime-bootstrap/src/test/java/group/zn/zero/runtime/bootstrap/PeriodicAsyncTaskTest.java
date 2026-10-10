package group.zn.zero.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 维护任务在 IO 域执行、不会重叠，关闭等待已经开始的任务。 @author zn */
class PeriodicAsyncTaskTest {
    /** 提交执行器抛 Error 后周期调度仍会重试，且失败必须可观测。 */
    @Test void submissionErrorDoesNotCancelPeriodicTask() throws Exception {
        var calls = new AtomicInteger();
        var recovered = new CountDownLatch(1);
        java.util.concurrent.Executor io = command -> {
            if (calls.incrementAndGet() == 1) throw new AssertionError("submission failed");
            command.run();
        };
        var constructor = ZeroRuntimeExecutors.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        try (var executors = (ZeroRuntimeExecutors) constructor.newInstance(
                io, io, io, io, java.util.List.of(), Duration.ofSeconds(1), false, false)) {
            var task = new PeriodicAsyncTask(executors, "submission-error", Duration.ofMillis(10), () -> {
                recovered.countDown();
                return CompletableFuture.completedFuture(null);
            });
            task.start();
            try {
                assertTrue(recovered.await(2, TimeUnit.SECONDS));
                assertEquals(1, task.failureCount());
                assertTrue(task.lastFailure() instanceof AssertionError);
            } finally {
                task.stop();
            }
        }
    }

    /** 停止维护时等待异步任务完成，然后关闭受管 timer。 */
    @Test void stopDrainsInFlightWorkAndTimerIsOwnedByRuntime() throws Exception {
        var entered = new CountDownLatch(1); var release = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        try (var executors = ZeroRuntimeExecutors.localPrototype("maintenance-test", 1)) {
            var task = new PeriodicAsyncTask(executors, "test", Duration.ofHours(1), () -> {
                assertTrue(Thread.currentThread().isVirtual()); calls.incrementAndGet(); entered.countDown(); return release;
            });
            task.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var stopped = CompletableFuture.runAsync(task::stop);
            assertFalse(stopped.isDone());
            release.complete(null); stopped.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals(0, task.failureCount());
            assertFalse(executors.scheduledExecutor("test").isShutdown());
        }
    }

    /** 内联执行域不能被周期维护任务借用。 */
    @Test void inlineIoIsRejected() {
        try (var executors = ZeroRuntimeExecutors.direct()) {
            var task = new PeriodicAsyncTask(executors, "test", Duration.ofSeconds(1),
                    () -> CompletableFuture.completedFuture(null));
            assertThrows(RuntimeException.class, task::start);
        }
    }
}
