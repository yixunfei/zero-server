package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 准入、直接执行器续调、公平性、取消与关闭的确定性回归。 @author zn */
class BoundedActorSchedulerTest {
    @Test void cancellingObservationDoesNotDiscardAnAcceptedQueuedMessage() {
        var scheduler = new LocalActorScheduler();
        CompletableFuture<Void> held = new CompletableFuture<>();
        AtomicInteger handled = new AtomicInteger();
        scheduler.register(String.class, (context, message) -> handled.getAndIncrement() == 0
                ? held : CompletableFuture.completedFuture(null));
        scheduler.dispatch(message("lane", "first"));
        var queued = scheduler.dispatch(message("lane", "second")).toCompletableFuture();
        queued.cancel(false);
        assertEquals(2, scheduler.statistics().pending());
        held.complete(null);
        assertEquals(2, handled.get());
        assertEquals(0, scheduler.statistics().pending());
    }

    @Test void capacityIncludesSuspendedAndCancelledHandler() {
        var scheduler = new ExecutorActorScheduler(Runnable::run, new ActorSchedulerConfig(2, 3, 1));
        CompletableFuture<Void> held = new CompletableFuture<>();
        scheduler.register(String.class, (ctx, msg) -> held);
        var first = scheduler.dispatch(message("a", "1")).toCompletableFuture();
        var second = scheduler.dispatch(message("a", "2")).toCompletableFuture();
        assertRejected(scheduler.dispatch(message("a", "3")).toCompletableFuture(), ActorErrorCode.CAPACITY_EXCEEDED);
        var other = scheduler.dispatch(message("b", "4")).toCompletableFuture();
        assertRejected(scheduler.dispatch(message("c", "5")).toCompletableFuture(), ActorErrorCode.CAPACITY_EXCEEDED);
        first.cancel(false);
        assertEquals(3, scheduler.statistics().pending());
        held.complete(null);
        second.join();
        other.join();
        assertEquals(0, scheduler.statistics().pending());
        assertEquals(0, scheduler.statistics().active());
    }

    @Test void hotLaneYieldsToColdLane() {
        ArrayDeque<Runnable> executor = new ArrayDeque<>();
        var scheduler = new ExecutorActorScheduler(executor::add, new ActorSchedulerConfig(20, 30, 2));
        List<String> order = new ArrayList<>();
        scheduler.register(String.class, ActorHandler.sync((ctx, msg) -> order.add((String) msg.payload())));
        for (int i = 0; i < 10; i++) scheduler.dispatch(message("hot", "hot"));
        var cold = scheduler.dispatch(message("cold", "cold")).toCompletableFuture();
        executor.remove().run();
        assertFalse(cold.isDone());
        executor.remove().run();
        assertTrue(cold.isDone());
        assertEquals(List.of("hot", "hot", "cold"), order);
        while (!executor.isEmpty()) executor.remove().run();
        assertEquals(0, scheduler.statistics().pending());
    }

    @Test void directExecutorDrainsLargeBacklogWithoutRecursion() {
        var scheduler = new LocalActorScheduler(new ActorSchedulerConfig(50_001, 50_001, 1));
        CompletableFuture<Void> held = new CompletableFuture<>();
        AtomicInteger handled = new AtomicInteger();
        scheduler.register(String.class, (ctx, msg) -> handled.getAndIncrement() == 0
                ? held : CompletableFuture.completedFuture(null));
        var first = scheduler.dispatch(message("lane", "a"));
        for (int i = 0; i < 50_000; i++) scheduler.dispatch(message("lane", "a"));
        held.complete(null);
        first.toCompletableFuture().join();
        assertEquals(50_001, handled.get());
        assertEquals(0, scheduler.statistics().pending());
    }

    @Test void rejectedContinuationReleasesCapacityAndAllowsRecovery() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AtomicInteger submitted = new AtomicInteger();
        var scheduler = new ExecutorActorScheduler(task -> {
            if (submitted.incrementAndGet() == 2) throw new java.util.concurrent.RejectedExecutionException();
            work.add(task);
        }, new ActorSchedulerConfig(10, 10, 1));
        scheduler.register(String.class, ActorHandler.sync((ctx, msg) -> { }));
        scheduler.dispatch(message("lane", "a"));
        var failed = scheduler.dispatch(message("lane", "b")).toCompletableFuture();
        work.remove().run();
        assertRejected(failed, ActorErrorCode.EXECUTOR_REJECTED);
        assertEquals(0, scheduler.statistics().pending());
        var recovered = scheduler.dispatch(message("lane", "c")).toCompletableFuture();
        work.remove().run();
        recovered.join();
    }

    @Test void closeFailsQueueButDoesNotReleaseRunningHandler() {
        var scheduler = new LocalActorScheduler();
        CompletableFuture<Void> held = new CompletableFuture<>();
        scheduler.register(String.class, (ctx, msg) -> held);
        var running = scheduler.dispatch(message("lane", "a")).toCompletableFuture();
        var queued = scheduler.dispatch(message("lane", "b")).toCompletableFuture();
        scheduler.close();
        assertRejected(queued, ActorErrorCode.SCHEDULER_CLOSED);
        assertEquals(1, scheduler.statistics().pending());
        assertRejected(scheduler.dispatch(message("new", "c")).toCompletableFuture(), ActorErrorCode.SCHEDULER_CLOSED);
        held.complete(null);
        running.join();
        assertEquals(0, scheduler.statistics().pending());
    }

    private static ActorMessage message(final String lane, final String payload) {
        return new ActorMessage(LaneKey.custom(lane), payload);
    }
    private static void assertRejected(final CompletableFuture<Void> future, final ActorErrorCode code) {
        CompletionException exception = assertThrows(CompletionException.class, future::join);
        assertEquals(code, ((ZeroException) exception.getCause()).errorCode());
    }
}
