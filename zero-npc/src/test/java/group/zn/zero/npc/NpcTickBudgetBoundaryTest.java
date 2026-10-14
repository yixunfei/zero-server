package group.zn.zero.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 零预算必须在调用业务决策前生效，并准确报告跳过数量。 @author zn */
class NpcTickBudgetBoundaryTest {
    /** 隔离普通业务异常但保留原因、错误码和当前 trace，后续 NPC 继续运行。 */
    @Test void isolatedFailureRetainsDiagnosticAndDoesNotStarveOtherNpcs() {
        var cause = group.zn.zero.core.error.ZeroException.of(NpcErrorCode.INVALID_STATE);
        var failures = new java.util.ArrayList<NpcEvent>();
        var successful = new AtomicInteger();
        var zone = new LocalNpcZone(new ZoneActor("errors", new LocalActorScheduler()),
                (npc, context) -> {
                    if (npc.npcId().value().equals("bad")) throw cause;
                    successful.incrementAndGet();
                    return new BehaviorDecision(npc.behavior(), "stay");
                }, event -> { if (event.failure() != null) failures.add(event); },
                event -> { }, new NoopNpcMetrics());
        zone.spawnAsync(npc("bad")).toCompletableFuture().join();
        zone.spawnAsync(npc("good")).toCompletableFuture().join();
        zone.tickAsync(new TickBudget(10000, 2), "decision-trace").toCompletableFuture().join();
        assertEquals(1, successful.get());
        assertEquals(1, failures.size());
        assertEquals("decision-trace", failures.getFirst().traceId());
        assertEquals(NpcErrorCode.INVALID_STATE, failures.getFirst().failure().errorCode());
        org.junit.jupiter.api.Assertions.assertSame(cause, failures.getFirst().failure().getCause());
        assertEquals(0, cause.getSuppressed().length);
    }

    /** 每轮三个决策，连续百轮后每个 NPC 必须恰好获得三次执行。 */
    @Test void rotatesAcrossEveryNpcWithoutStarvation() {
        var calls = new java.util.HashMap<NpcId, Integer>();
        var zone = new LocalNpcZone(new ZoneActor("fair", new LocalActorScheduler()),
                (npc, context) -> {
                    calls.merge(npc.npcId(), 1, Integer::sum);
                    return new BehaviorDecision(npc.behavior(), "stay");
                }, event -> { }, event -> { }, new NoopNpcMetrics());
        for (int index = 0; index < 100; index++) zone.spawnAsync(npc("npc-" + index)).toCompletableFuture().join();
        for (int tick = 0; tick < 100; tick++) {
            assertEquals(3, zone.tickAsync(new TickBudget(10000, 3), "tick")
                    .toCompletableFuture().join().processedCount());
        }
        assertEquals(100, calls.size());
        calls.values().forEach(count -> assertEquals(3, count));
    }

    /** 删除下一个候选、插入新节点及零预算均不能损坏续扫位置。 */
    @Test void removalAndInsertionPreserveRotation() {
        var calls = new java.util.ArrayList<String>();
        var zone = new LocalNpcZone(new ZoneActor("mutations", new LocalActorScheduler()),
                (npc, context) -> {
                    calls.add(npc.npcId().value());
                    return new BehaviorDecision(npc.behavior(), "stay");
                }, event -> { }, event -> { }, new NoopNpcMetrics());
        for (String id : new String[] {"a", "b", "c"}) zone.spawnAsync(npc(id)).toCompletableFuture().join();
        zone.tickAsync(new TickBudget(10000, 1), "tick").toCompletableFuture().join();
        zone.despawnAsync(new NpcId("b"), "remove").toCompletableFuture().join();
        zone.spawnAsync(npc("d")).toCompletableFuture().join();
        zone.tickAsync(new TickBudget(10000, 0), "zero").toCompletableFuture().join();
        zone.tickAsync(new TickBudget(10000, 3), "tick").toCompletableFuture().join();
        assertEquals(java.util.List.of("a", "c", "a", "d"), calls);
    }

    /** 零时间和零操作预算都不能执行首个 NPC；后续有预算 tick 可正常恢复。 */
    @Test void zeroBudgetSkipsEveryNpcAndLaterTicksStillRun() {
        for (TickBudget budget : new TickBudget[] {new TickBudget(0, 10), new TickBudget(1000, 0)}) {
            var calls = new AtomicInteger();
            var zone = new LocalNpcZone(new ZoneActor("budget", new LocalActorScheduler()),
                    (npc, context) -> {
                        calls.incrementAndGet();
                        return new BehaviorDecision(npc.behavior(), "unchanged");
                    }, event -> { }, event -> { }, new NoopNpcMetrics());
            zone.spawnAsync(npc("first")).toCompletableFuture().join();
            zone.spawnAsync(npc("second")).toCompletableFuture().join();
            TickResult skipped = zone.tickAsync(budget, "skip").toCompletableFuture().join();
            assertEquals(0, calls.get());
            assertEquals(0, skipped.processedCount());
            assertEquals(2, skipped.skippedCount());
            assertEquals(TickResult.Outcome.SKIPPED, skipped.outcome());
            TickResult resumed = zone.tickAsync(new TickBudget(1000, 1), "resume").toCompletableFuture().join();
            assertEquals(1, calls.get());
            assertEquals(1, resumed.processedCount());
            assertEquals(1, resumed.skippedCount());
        }
    }

    private static NpcSnapshot npc(final String id) {
        return new NpcSnapshot(new NpcId(id), "template", null, null, null,
                NpcLifecycleState.CREATED, BehaviorState.idle(), 0, 0, 0, "spawn");
    }
}
