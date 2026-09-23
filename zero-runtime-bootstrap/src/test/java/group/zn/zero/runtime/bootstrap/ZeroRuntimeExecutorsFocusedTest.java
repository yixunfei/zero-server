package group.zn.zero.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 starter 执行域的线程归属、资源管理和关闭边界。
 *
 * @author zn
 */
class ZeroRuntimeExecutorsFocusedTest {

    /** 验证四个执行域均使用受管线程，且不以内联方式运行。 */
    @Test
    void localPrototypeKeepsExecutionDomainsAndThreadOwnership() throws Exception {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("wp02-focused", 1)) {
            assertFalse(executors.remoteIoMayInline());
            assertFalse(executors.backgroundMayInline());
            assertEquals("wp02-focused-logic-1", submitAndRead(executors.logicExecutor()));
            assertEquals("wp02-focused-actor-1", submitAndRead(executors.actorExecutor()));
            assertTrue(submitAndRead(executors.remoteIoExecutor()).startsWith("wp02-focused-remote-io-"));
            assertEquals("wp02-focused-background-1", submitAndRead(executors.backgroundExecutor()));
        }
    }

    /** 验证关闭会停止全部受管线程，并使执行器拒绝新的任务。 */
    @Test
    void closeStopsAllOwnedExecutorsAndRejectsSubmissions() throws Exception {
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("wp02-close", 1);
        submitAndRead(executors.logicExecutor());
        submitAndRead(executors.actorExecutor());
        submitAndRead(executors.remoteIoExecutor());
        submitAndRead(executors.backgroundExecutor());

        executors.close();
        executors.close();

        for (var executor : Set.of(executors.logicExecutor(), executors.actorExecutor(),
                executors.remoteIoExecutor(), executors.backgroundExecutor())) {
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        }
    }

    /** 验证 direct 装配不拥有线程池，任务在调用线程内联完成。 */
    @Test
    void directDoesNotCreateOrOwnBackgroundThreads() {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.direct()) {
            String caller = Thread.currentThread().getName();
            assertEquals(caller, submitAndRead(executors.logicExecutor()));
            assertEquals(caller, submitAndRead(executors.actorExecutor()));
            assertEquals(caller, submitAndRead(executors.remoteIoExecutor()));
            assertEquals(caller, submitAndRead(executors.backgroundExecutor()));
            assertTrue(executors.remoteIoMayInline());
            assertTrue(executors.backgroundMayInline());
        }
    }

    private String submitAndRead(final java.util.concurrent.Executor executor) {
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        executor.execute(() -> {
            thread.set(Thread.currentThread().getName());
            completed.countDown();
        });
        try {
            assertTrue(completed.await(1, TimeUnit.SECONDS), "managed executor did not complete task");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting managed executor", interrupted);
        }
        return thread.get();
    }
}
