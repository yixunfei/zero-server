package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * executor-backed Actor 调度器测试。
 *
 * @author zn
 */
class ExecutorActorSchedulerTest {

    /**
     * 验证同一 lane 的消息在外部执行器中按顺序执行。
     */
    @Test
    void shouldRunSameLaneMessagesInOrderOnExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(
                runnable,
                "zero-test-actor"));
        try {
            ActorScheduler scheduler = new ExecutorActorScheduler(executor);
            List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
            scheduler.register(String.class, ActorHandler.sync((context, message) -> {
                steps.add(message.payload() + "@" + Thread.currentThread().getName());
            }));

            scheduler.dispatch(new ActorMessage("m1", LaneKey.player("1001"), "trace-1", "first"))
                    .toCompletableFuture()
                    .join();
            scheduler.dispatch(new ActorMessage("m2", LaneKey.player("1001"), "trace-2", "second"))
                    .toCompletableFuture()
                    .join();

            assertEquals(List.of("first@zero-test-actor", "second@zero-test-actor"), steps);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证异步 handler 未完成时，同一 lane 后续消息不会越过执行。
     */
    @Test
    void shouldWaitForAsyncHandlerBeforeDrainingNextMessageInSameLane() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ActorScheduler scheduler = new ExecutorActorScheduler(executor);
            CompletableFuture<Void> firstCompletion = new CompletableFuture<>();
            List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
            scheduler.register(String.class, (context, message) -> {
                steps.add((String) message.payload());
                if ("first".equals(message.payload())) {
                    return firstCompletion;
                }
                return CompletableFuture.completedFuture(null);
            });

            CompletableFuture<Void> first = scheduler
                    .dispatch(new ActorMessage("m1", LaneKey.scene("scene-1"), "trace-1", "first"))
                    .toCompletableFuture();
            CompletableFuture<Void> second = scheduler
                    .dispatch(new ActorMessage("m2", LaneKey.scene("scene-1"), "trace-2", "second"))
                    .toCompletableFuture();

            awaitStepCount(steps, 1);
            assertEquals(List.of("first"), steps);

            firstCompletion.complete(null);
            CompletableFuture.allOf(first, second).join();

            assertEquals(List.of("first", "second"), steps);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 验证异步完成、异常、取消和超时均不会越过同 lane 的完成边界。 */
    @Test
    void shouldPropagateAsyncFailureCancellationAndTimeout() {
        ExecutorService executor = Executors.newSingleThreadExecutor(named("zero-async-actor-"));
        try {
            ActorScheduler scheduler = new ExecutorActorScheduler(executor);
            scheduler.register(String.class, (context, message) -> switch ((String) message.payload()) {
                case "failure" -> CompletableFuture.failedFuture(new IllegalStateException("async boom"));
                case "cancel" -> { CompletableFuture<Void> cancelled = new CompletableFuture<>(); cancelled.cancel(false); yield cancelled; }
                case "timeout" -> new CompletableFuture<Void>().orTimeout(40, TimeUnit.MILLISECONDS);
                default -> CompletableFuture.completedFuture(null);
            });

            assertSystemFailure(scheduler.dispatch(new ActorMessage(LaneKey.player("failure"), "failure")), "async boom");
            assertThrows(CompletionException.class,
                    () -> scheduler.dispatch(new ActorMessage(LaneKey.player("cancel"), "cancel"))
                            .toCompletableFuture().join());
            assertSystemFailureCode(scheduler.dispatch(new ActorMessage(LaneKey.player("timeout"), "timeout")));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 验证异步 Actor 工作线程不会等待未完成 stage，其他 lane 仍可运行。 */
    @Test
    void shouldNotBlockActorThreadWhileAsyncHandlerIsPending() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(named("zero-blocking-detector-"));
        try {
            ActorScheduler scheduler = new ExecutorActorScheduler(executor);
            CompletableFuture<Void> pending = new CompletableFuture<>();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch otherLaneEntered = new CountDownLatch(1);
            scheduler.register(String.class, (context, message) -> {
                if ("pending".equals(message.payload())) {
                    firstEntered.countDown();
                    return pending;
                }
                otherLaneEntered.countDown();
                return CompletableFuture.completedFuture(null);
            });

            CompletableFuture<Void> first = scheduler.dispatch(new ActorMessage(LaneKey.player("p1"), "pending"))
                    .toCompletableFuture();
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> other = scheduler.dispatch(new ActorMessage(LaneKey.player("p2"), "other"))
                    .toCompletableFuture();
            assertTrue(otherLaneEntered.await(1, TimeUnit.SECONDS), "actor executor blocked on pending stage");
            assertFalse(first.isDone());
            pending.complete(null);
            assertTrue(first.completeOnTimeout(null, 1, TimeUnit.SECONDS).join() == null);
            assertTrue(other.join() == null);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    /** 验证执行器拒绝提交时所有排队消息都释放为失败结果。 */
    @Test
    void shouldFailQueuedMessagesWhenExecutorRejects() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        ActorScheduler scheduler = new ExecutorActorScheduler(executor);
        scheduler.register(String.class, ActorHandler.sync((context, message) -> { }));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> scheduler.dispatch(new ActorMessage(LaneKey.player("p1"), "payload"))
                        .toCompletableFuture().join());
        assertInstanceOf(ZeroException.class, failure.getCause());
        assertEquals(SystemErrorCode.SYSTEM_ERROR, ((ZeroException) failure.getCause()).errorCode());
    }

    private void assertSystemFailure(final java.util.concurrent.CompletionStage<Void> stage, final String message) {
        CompletionException failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        ZeroException zero = assertInstanceOf(ZeroException.class, failure.getCause());
        assertEquals(SystemErrorCode.SYSTEM_ERROR, zero.errorCode());
        assertTrue(zero.getMessage().contains(message));
    }

    private void assertSystemFailureCode(final java.util.concurrent.CompletionStage<Void> stage) {
        CompletionException failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        ZeroException zero = assertInstanceOf(ZeroException.class, failure.getCause());
        assertEquals(SystemErrorCode.SYSTEM_ERROR, zero.errorCode());
    }
    private static java.util.concurrent.ThreadFactory named(final String prefix) {
        return runnable -> new Thread(runnable, prefix + System.nanoTime());
    }

    private void awaitStepCount(final List<String> steps, final int expectedSize) {
        assertTimeout(Duration.ofSeconds(3), () -> {
            while (steps.size() < expectedSize) {
                Thread.onSpinWait();
            }
        });
    }
}
