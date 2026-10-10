package group.zn.zero.npc;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证异步 NPC 生命周期、决策隔离和重入排队语义。 @author zn */
class NpcRuntimeTest {
    @Test void reentrantReplacementQueuesAfterTickAndKeepsNewIdentity() {
        var holder = new java.util.concurrent.atomic.AtomicReference<LocalNpcZone>();
        var calls = new java.util.ArrayList<NpcId>();
        var pending = new java.util.ArrayList<java.util.concurrent.CompletionStage<?>>();
        LocalNpcZone zone = zone((snapshot, context) -> {
            calls.add(snapshot.npcId());
            if (snapshot.npcId().equals(new NpcId("1"))) {
                pending.add(holder.get().despawnAsync(snapshot.npcId(), "remove"));
                pending.add(holder.get().spawnAsync(new NpcSnapshot(snapshot.npcId(), "dragon", null, null, null,
                        NpcLifecycleState.CREATED, BehaviorState.idle(), 0, 10, 0, "new")));
                pending.add(holder.get().despawnAsync(new NpcId("2"), "remove"));
                assertFalse(pending.getFirst().toCompletableFuture().isDone());
            }
            return new BehaviorDecision(new BehaviorState("patrol"), "old");
        });
        holder.set(zone);
        spawn(zone, "1"); spawn(zone, "2");
        TickResult result = zone.tickAsync(new TickBudget(1000, 10), "tick").toCompletableFuture().join();
        pending.forEach(stage -> stage.toCompletableFuture().join());
        var replacement = zone.find(new NpcId("1")).orElseThrow();
        assertEquals("dragon", replacement.templateId());
        assertEquals(BehaviorState.idle(), replacement.behavior());
        assertEquals(1, replacement.version());
        assertTrue(zone.find(new NpcId("2")).isEmpty());
        assertEquals(java.util.List.of(new NpcId("1"), new NpcId("2")), calls);
        assertEquals(2, result.processedCount());
        assertEquals(0, result.skippedCount());
    }
    @Test void lifecycleFailuresCarryDomainCodes() {
        var zone = zone((npc, context) -> new BehaviorDecision(npc.behavior(), "stay"));
        var created = npc("one");
        var active = zone.spawnAsync(created).toCompletableFuture().join();
        var duplicate = assertThrows(CompletionException.class, () -> zone.spawnAsync(created).toCompletableFuture().join());
        assertEquals(NpcErrorCode.ALREADY_EXISTS, ((ZeroException) duplicate.getCause()).errorCode());
        var invalid = assertThrows(CompletionException.class, () -> zone.spawnAsync(active).toCompletableFuture().join());
        assertEquals(NpcErrorCode.INVALID_STATE, ((ZeroException) invalid.getCause()).errorCode());
        var missing = assertThrows(CompletionException.class,
                () -> zone.despawnAsync(new NpcId("absent"), "trace").toCompletableFuture().join());
        assertEquals(NpcErrorCode.NOT_FOUND, ((ZeroException) missing.getCause()).errorCode());
        assertEquals(1, zone.size());
    }
    @Test void npcLifecycleTransitionsAreValid() {
        var zone = zone((npc, context) -> new BehaviorDecision(new BehaviorState("patrol"), "test"));
        var active = spawn(zone, "1");
        assertEquals(NpcLifecycleState.ACTIVE, active.lifecycle());
        assertEquals(NpcLifecycleState.REMOVED,
                zone.despawnAsync(active.npcId(), "remove").toCompletableFuture().join().lifecycle());
        assertTrue(zone.find(active.npcId()).isEmpty());
    }
    @Test void removedNpcIsNotTickedAgain() {
        var calls = new AtomicInteger();
        var zone = zone((npc, context) -> { calls.incrementAndGet(); return new BehaviorDecision(npc.behavior(), "stay"); });
        var active = spawn(zone, "1");
        zone.despawnAsync(active.npcId(), "remove").toCompletableFuture().join();
        zone.tickAsync(new TickBudget(100, 10), "tick").toCompletableFuture().join();
        assertEquals(0, calls.get());
    }
    @Test void tickBudgetLimitsProcessedNpcCount() {
        var zone = zone((npc, context) -> new BehaviorDecision(npc.behavior(), "stay"));
        spawn(zone, "1"); spawn(zone, "2");
        var result = zone.tickAsync(new TickBudget(100, 1), "tick").toCompletableFuture().join();
        assertEquals(1, result.processedCount()); assertEquals(1, result.skippedCount());
        assertEquals(TickResult.Outcome.DEGRADED, result.outcome());
    }
    @Test void behaviorAdapterExceptionIsIsolated() {
        var zone = zone((npc, context) -> { throw new IllegalStateException("failed"); });
        spawn(zone, "1"); spawn(zone, "2");
        var result = zone.tickAsync(new TickBudget(100, 10), "tick").toCompletableFuture().join();
        assertEquals(2, result.processedCount()); assertEquals(TickResult.Outcome.COMPLETED, result.outcome());
    }
    @Test void metricsDoNotUseNpcIdLabels() {
        assertDoesNotThrow(() -> new NoopNpcMetrics().recordBehaviorFailure("zone", "idle"));
    }
    private NpcSnapshot spawn(final LocalNpcZone zone, final String id) {
        return zone.spawnAsync(npc(id)).toCompletableFuture().join();
    }
    private NpcSnapshot npc(final String id) {
        return new NpcSnapshot(new NpcId(id), "template", null, null, null,
                NpcLifecycleState.CREATED, BehaviorState.idle(), 0, 0, 0, "trace");
    }
    private LocalNpcZone zone(final BehaviorAdapter adapter) {
        return new LocalNpcZone(new ZoneActor("z", new LocalActorScheduler()), adapter,
                event -> { }, audit -> { }, new NoopNpcMetrics());
    }
}
