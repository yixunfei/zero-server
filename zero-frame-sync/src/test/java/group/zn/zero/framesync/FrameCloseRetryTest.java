package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 清理拒绝可观察、可重试，取消观察者不影响同 lane 清理。 @author zn */
class FrameCloseRetryTest {
    /** 真实执行器拒绝后允许恢复，且失败期间始终拒绝业务输入。 */
    @Test void executorRejectionIsVisibleAndRetryClearsRetainedPayload() throws Exception {
        var reject = new AtomicBoolean();
        try (var scheduler = new ExecutorActorScheduler(task -> {
            if (reject.get()) throw new RejectedExecutionException("controlled");
            task.run();
        })) {
            var runtime = runtime(scheduler);
            runtime.submit(input()).toCompletableFuture().join();
            reject.set(true);
            assertThrows(CompletionException.class, runtime::close);
            assertEquals(1, pending(runtime));
            assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
            reject.set(false);
            runtime.closeAsync().toCompletableFuture().join();
            assertEquals(0, pending(runtime));
            runtime.close();
            try (var other = runtime(scheduler)) {
                other.tick().toCompletableFuture().join();
            }
        }
    }

    /** 在途重复调用不多投递；同步入口不谎报完成，也不阻塞等待当前 lane。 */
    @Test void queuedCloseDeduplicatesAndObserverCancellationCannotCancelCleanup() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        try (var scheduler = new ExecutorActorScheduler(tasks::addLast)) {
            var runtime = runtime(scheduler);
            runtime.submit(input());
            drain(tasks);
            var cancelled = runtime.closeAsync().toCompletableFuture();
            var observer = runtime.closeAsync().toCompletableFuture();
            assertEquals(1, tasks.size());
            cancelled.cancel(false);
            assertFalse(observer.isDone());
            assertThrows(ZeroException.class, runtime::close);
            drain(tasks);
            observer.join();
            assertEquals(0, pending(runtime));
            runtime.close();
            assertTrue(tasks.isEmpty());
        }
    }

    /** SPI 同步抛出及后续异常完成都必须传播，并可在同一关闭实例上重试。 */
    @Test void synchronousAndDeferredDispatchFailuresCanRetry() throws Exception {
        try (var delegate = new LocalActorScheduler()) {
            var scheduler = new FailingDispatch(delegate);
            var runtime = runtime(scheduler);
            runtime.submit(input()).toCompletableFuture().join();
            scheduler.throwNow = true;
            assertThrows(CompletionException.class, () -> runtime.closeAsync().toCompletableFuture().join());
            scheduler.throwNow = false;
            scheduler.deferred = new CompletableFuture<>();
            var failed = runtime.closeAsync().toCompletableFuture();
            assertFalse(failed.isDone());
            scheduler.deferred.completeExceptionally(new RejectedExecutionException("later"));
            assertThrows(CompletionException.class, failed::join);
            assertEquals(1, pending(runtime));
            scheduler.deferred = null;
            runtime.closeAsync().toCompletableFuture().join();
            assertEquals(0, pending(runtime));
        }
    }

    private static void drain(final ArrayDeque<Runnable> tasks) {
        while (!tasks.isEmpty()) tasks.removeFirst().run();
    }

    private static int pending(final FrameMatchRuntime runtime) throws Exception {
        var field = FrameMatchRuntime.class.getDeclaredField("pending");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(runtime)).size();
    }

    private static FrameInput input() { return new FrameInput("u", 1, 1, 0, new byte[]{1}, "trace"); }

    private static FrameMatchRuntime runtime(final ActorScheduler scheduler) {
        return new FrameMatchRuntime("retry", FrameMatchConfig.defaults(),
                (frame, batch) -> { }, event -> { }, event -> { }, scheduler);
    }

    /** 可控失败的调度边界，其成功路径使用真实本地处理器。 */
    private static final class FailingDispatch implements ActorScheduler {
        /** 成功路径。 */
        private final ActorScheduler delegate;
        /** 控制同步拒绝。 */
        private boolean throwNow;
        /** 控制延迟拒绝。 */
        private CompletableFuture<Void> deferred;

        private FailingDispatch(final ActorScheduler delegate) { this.delegate = delegate; }

        @Override public ActorSubscription register(final Class<?> type, final ActorHandler handler) {
            return delegate.register(type, handler);
        }

        @Override public CompletionStage<Void> dispatch(final ActorMessage message) {
            if (throwNow) throw new RejectedExecutionException("immediate");
            return deferred == null ? delegate.dispatch(message) : deferred;
        }
    }
}
