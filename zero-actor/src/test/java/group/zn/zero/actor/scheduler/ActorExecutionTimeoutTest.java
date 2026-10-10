package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 执行超时只隔离和通知，不伪造真实完成或释放串行许可。 @author zn */
class ActorExecutionTimeoutTest {
    @Test void hangingHandlerIsIsolatedUntilItsActualCompletion() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var scheduler = new ExecutorActorScheduler(Runnable::run, new ActorSchedulerConfig(8, 8, 8),
                     timer, Duration.ofMillis(100))) {
            var actual = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            scheduler.register(String.class, (context, message) -> {
                calls.incrementAndGet();
                return message.payload().equals("hang") ? actual : CompletableFuture.completedFuture(null);
            });
            var lane = LaneKey.player("p");
            var first = scheduler.dispatch(new ActorMessage(lane, "hang")).toCompletableFuture();
            var queued = scheduler.dispatch(new ActorMessage(lane, "queued")).toCompletableFuture();
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> scheduler.dispatch(new ActorMessage(lane, "late"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(1, scheduler.statistics().pending());
            assertEquals(1, scheduler.executionTimeoutCount());
            actual.complete(null);
            scheduler.dispatch(new ActorMessage(lane, "resumed")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
        }
    }
}
