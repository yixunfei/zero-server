package group.zn.zero.npc;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 GM、定时 tick 和查询并发时不会破坏 zone 内部索引。 */
class LocalNpcZoneConcurrencyTest {

    /** 并发 spawn、tick 与 despawn 结束后，NPC 索引应保持一致且不抛出结构并发错误。 */
    @Test
    void concurrentLifecycleCallsPreserveNpcIndex() throws Exception {
        LocalNpcZone zone = new LocalNpcZone(
                new ZoneActor("concurrent", new LocalActorScheduler()),
                (snapshot, context) -> new BehaviorDecision(snapshot.behavior(), "unchanged"),
                event -> { }, audit -> { }, new NoopNpcMetrics());
        try (ExecutorService workers = Executors.newFixedThreadPool(3)) {
            CompletableFuture<Void> firstHalf = CompletableFuture.runAsync(() -> spawnRange(zone, 0, 200), workers);
            CompletableFuture<Void> secondHalf = CompletableFuture.runAsync(() -> spawnRange(zone, 200, 400), workers);
            CompletableFuture.allOf(firstHalf, secondHalf).get(5L, TimeUnit.SECONDS);

            CompletableFuture<Void> ticking = CompletableFuture.runAsync(() -> {
                for (int attempt = 0; attempt < 20; attempt++) {
                    zone.tickAsync(new TickBudget(1_000, 10), "tick-" + attempt).toCompletableFuture().join();
                }
            }, workers);
            CompletableFuture<Void> despawning = CompletableFuture.runAsync(() -> {
                for (int index = 0; index < 400; index++) {
                    zone.despawnAsync(new NpcId("npc-" + index), "gm").toCompletableFuture().join();
                }
            }, workers);
            CompletableFuture.allOf(ticking, despawning).get(5L, TimeUnit.SECONDS);
        }
        assertEquals(0, zone.size());
    }

    /** 在调用方线程构造已创建 NPC，便于并发测试复用。 */
    private static void spawnRange(final LocalNpcZone zone, final int fromInclusive, final int toExclusive) {
        for (int index = fromInclusive; index < toExclusive; index++) {
            zone.spawnAsync(new NpcSnapshot(
                    new NpcId("npc-" + index), "test", null, null, null,
                    NpcLifecycleState.CREATED, BehaviorState.idle(), 0L, 0L, 0L, "spawn")).toCompletableFuture().join();
        }
    }
}
