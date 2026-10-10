package group.zn.zero.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证真实区域 lane 顺序、锁外回调和失败通知边界。 @author zn */
class NpcLaneBoundaryTest {
    @Test void mutationsShareZoneLaneWithOtherActorMessagesAndZonesShareScheduler() {
        var tasks = new ArrayDeque<Runnable>();
        try (var scheduler = new ExecutorActorScheduler(tasks::addLast)) {
            var actor = new ZoneActor("zone", scheduler);
            var gate = new CompletableFuture<Void>();
            scheduler.register(String.class, (context, message) -> gate);
            scheduler.dispatch(new ActorMessage(actor.laneKey(), "hold"));
            tasks.removeFirst().run();
            var zone = new LocalNpcZone(actor, (npc, context) -> new BehaviorDecision(npc.behavior(), "stay"),
                    event -> { }, event -> { }, new NoopNpcMetrics());
            var other = new LocalNpcZone(new ZoneActor("other", scheduler),
                    (npc, context) -> new BehaviorDecision(npc.behavior(), "stay"),
                    event -> { }, event -> { }, new NoopNpcMetrics());
            var first = zone.spawnAsync(npc("same")).toCompletableFuture();
            var second = zone.despawnAsync(new NpcId("same"), "remove").toCompletableFuture();
            var independent = other.spawnAsync(npc("same")).toCompletableFuture();
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            assertFalse(first.isDone()); assertFalse(second.isDone());
            assertTrue(independent.isDone()); assertEquals(0, zone.size());
            gate.complete(null);
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            first.join(); second.join();
            assertEquals(0, zone.size()); assertEquals(1, other.size());
        }
    }

    @Test void callbacksCanAwaitConcurrentQueriesAndNotificationsAllRunAfterCommit() {
        var reference = new AtomicReference<LocalNpcZone>();
        var calls = new ArrayList<String>();
        var shared = new IllegalStateException("sink");
        var zone = new LocalNpcZone(new ZoneActor("zone", new LocalActorScheduler()),
                (npc, context) -> new BehaviorDecision(new BehaviorState("patrol"), "change"),
                event -> {
                    try {
                        assertEquals(1, CompletableFuture.supplyAsync(() -> reference.get().size()).get(1, TimeUnit.SECONDS));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                    calls.add(event.type().name());
                    throw shared;
                }, event -> { calls.add("audit"); throw shared; }, new NpcMetrics() {
                    @Override public void recordTick(final TickResult result) { }
                    @Override public void recordBehaviorFailure(final String zoneId, final String behavior) {
                        calls.add("behavior-failure");
                    }
                    @Override public void recordSpawn() { calls.add("metric"); }
                });
        reference.set(zone);
        var failed = assertThrows(CompletionException.class, () -> zone.spawnAsync(npc("npc")).toCompletableFuture().join());
        assertEquals(List.of("SPAWNED", "audit", "metric"), calls);
        assertEquals(1, failed.getCause().getSuppressed().length);
        assertEquals(0, shared.getSuppressed().length);
        calls.clear();
        assertThrows(CompletionException.class, () -> zone.tickAsync(new TickBudget(1000, 1), "tick").toCompletableFuture().join());
        assertEquals(List.of("BEHAVIOR_CHANGED"), calls);
        assertEquals(new BehaviorState("patrol"), zone.find(new NpcId("npc")).orElseThrow().behavior());
    }

    @Test void adapterErrorCompletesExceptionallyAndStillRecordsTick() {
        var ticks = new AtomicInteger();
        var events = new ArrayList<NpcEvent.Type>();
        var zone = new LocalNpcZone(new ZoneActor("zone", new LocalActorScheduler()),
                (npc, context) -> { throw new AssertionError("adapter"); },
                event -> events.add(event.type()), event -> { }, new NpcMetrics() {
                    @Override public void recordBehaviorFailure(final String zoneId, final String behavior) { }
                    @Override public void recordTick(final TickResult result) {
                        assertEquals(1, result.skippedCount()); ticks.incrementAndGet();
                    }
                });
        zone.spawnAsync(npc("npc")).toCompletableFuture().join();
        var failure = assertThrows(CompletionException.class,
                () -> zone.tickAsync(new TickBudget(1000, 1), "tick").toCompletableFuture().join());
        assertInstanceOf(AssertionError.class, failure.getCause().getCause());
        assertEquals(1, ticks.get());
        assertEquals(List.of(NpcEvent.Type.SPAWNED), events);
    }
    private static NpcSnapshot npc(final String id) {
        return new NpcSnapshot(new NpcId(id), "template", null, null, null,
                NpcLifecycleState.CREATED, BehaviorState.idle(), 0, 0, 0, "trace");
    }
}
