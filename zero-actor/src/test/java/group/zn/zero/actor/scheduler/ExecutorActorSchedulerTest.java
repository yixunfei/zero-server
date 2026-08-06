package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private void awaitStepCount(final List<String> steps, final int expectedSize) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (steps.size() >= expectedSize) {
                return;
            }
            Thread.onSpinWait();
        }
    }
}
