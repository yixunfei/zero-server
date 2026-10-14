package group.zn.zero.runtime.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 持久化调度适配必须等到真实异步完成后才允许停止成功。 @author zn */
class ManagedPersistenceSchedulerTest {
    /** 动作运行在受管 IO，取消等待在途结果，异步失败可观测。 */
    @Test void cancellationDrainsAndRetainsAsyncFailure() throws Exception {
        try (var executors = ZeroRuntimeExecutors.localPrototype("persistence-schedule", 1)) {
            var scheduler = new ManagedPersistenceScheduler(executors);
            var entered = new CountDownLatch(1);
            var completion = new CompletableFuture<Void>();
            var handle = scheduler.scheduleAtFixedRate("save", Duration.ofHours(1), () -> {
                assertTrue(Thread.currentThread().isVirtual());
                entered.countDown();
                return completion;
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var stopped = CompletableFuture.runAsync(handle::cancel);
                var cause = new IllegalStateException("async write failed");
                completion.completeExceptionally(cause);
                stopped.get(3, TimeUnit.SECONDS);
                assertTrue(handle.cancelled());
                assertEquals(1, handle.failureCount());
                assertSame(cause, handle.lastFailure());
            } finally {
                completion.complete(null);
                handle.cancel();
            }
        }
    }
}
