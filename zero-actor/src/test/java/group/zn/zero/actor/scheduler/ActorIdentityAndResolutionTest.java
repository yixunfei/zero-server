package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 关联 ID 与有序解析缓存的公开语义验证。 @author zn */
class ActorIdentityAndResolutionTest {
    @Test void registrationSnapshotsInvalidatePositiveAndNegativeResolution() {
        for (ActorScheduler scheduler : List.of(new LocalActorScheduler(), new ExecutorActorScheduler(Runnable::run))) {
            List<String> calls = new ArrayList<>();
            ActorMessage message = new ActorMessage(LaneKey.custom("test"), "hello");
            assertThrows(RuntimeException.class, () -> scheduler.dispatch(message));
            var broad = scheduler.register(CharSequence.class, ActorHandler.sync((ctx, msg) -> calls.add("broad")));
            scheduler.dispatch(message).toCompletableFuture().join();
            ActorHandler exactHandler = ActorHandler.sync((ctx, msg) -> calls.add("exact"));
            var exact = scheduler.register(String.class, exactHandler);
            scheduler.dispatch(message).toCompletableFuture().join();
            exact.close();
            scheduler.dispatch(message).toCompletableFuture().join();
            var replacement = scheduler.register(String.class, exactHandler);
            exact.close();
            scheduler.dispatch(message).toCompletableFuture().join();
            replacement.close();
            broad.close();
            assertThrows(RuntimeException.class, () -> scheduler.dispatch(message));
            assertEquals(List.of("broad", "exact", "broad", "exact"), calls);
        }
    }

    @Test void concurrentDefaultsAreUniqueAndExplicitTraceIsPreserved() throws Exception {
        var ids = ConcurrentHashMap.<String>newKeySet();
        var workers = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> {
                for (int j = 0; j < 5000; j++) {
                    ActorMessage message = new ActorMessage(LaneKey.custom("lane"), "payload");
                    assertTrue(ids.add(message.messageId()));
                    assertEquals(message.messageId(), message.traceId());
                }
            }));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(40_000, ids.size());
            var explicit = new ActorMessage("id", LaneKey.custom("lane"), "trace", "payload");
            assertEquals("id", explicit.messageId());
            assertEquals("trace", new ActorMessage(explicit.laneKey(), explicit.traceId(), "next").traceId());
        } finally { workers.shutdownNow(); }
    }
}
