package group.zn.zero.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 零预算必须在调用业务决策前生效，并准确报告跳过数量。 @author zn */
class NpcTickBudgetBoundaryTest {
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
