package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 持续并发超限时严格限制总积压，恢复后每个已准入消息恰好执行一次。 @author zn */
class ActorOverloadTest {
    @Test void concurrentAdmissionNeverExceedsGlobalLimit() throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        var producers = Executors.newFixedThreadPool(32);
        var scheduler = new ExecutorActorScheduler(workers, new ActorSchedulerConfig(8, 128, 4));
        var held = new CompletableFuture<Void>();
        var handled = new AtomicInteger();
        var accepted = new ConcurrentLinkedQueue<CompletableFuture<Void>>();
        scheduler.register(String.class, (ctx, message) -> { handled.incrementAndGet(); return held; });
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int producer = 0; producer < 32; producer++) tasks.add(producers.submit(() -> {
                for (int i = 0; i < 512; i++) {
                    var result = scheduler.dispatch(new ActorMessage(LaneKey.custom("l" + i % 64), "payload"))
                            .toCompletableFuture();
                    if (!result.isCompletedExceptionally()) accepted.add(result);
                    assertTrue(scheduler.statistics().pending() <= 128);
                }
            }));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(128, scheduler.statistics().pending());
            assertEquals(128, accepted.size());
            assertEquals(32 * 512 - 128, scheduler.statistics().rejected());
            held.complete(null);
            CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(128, handled.get());
            assertEquals(0, scheduler.statistics().pending());
        } finally { scheduler.close(); producers.shutdownNow(); workers.shutdownNow(); }
    }
}
