package group.zn.zero.examples.npctick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Focused smoke tests for the local NPC tick example. */
class NpcTickDemoTest {
    @Test
    void markerIsStableAndExplicitlyLocal() {
        NpcTickDemo.Result result = NpcTickDemo.run();
        assertEquals("npc-tick=ok|mode=local|ticks=2|steps=2|behavior=patrol|position=12,20|productionReady=false", result.marker());
    }

    @Test
    void tickBudgetLimitsProcessedNpcSteps() {
        var api = new NpcTickDemo.LocalNpcTickApi(new NpcTickDemo.Limits(4, 1));
        api.spawn(1, "zone", 0, 0);
        api.spawn(2, "zone", 0, 0);
        api.setBehavior(1, "patrol");
        api.setBehavior(2, "patrol");
        api.tick("zone", 1);
        assertEquals(1, api.processedNpcSteps());
        assertEquals(1, api.query(1).x());
        assertEquals(0, api.query(2).x());
    }

    @Test
    void capacityAndUnsupportedBehaviorAreRejected() {
        var api = new NpcTickDemo.LocalNpcTickApi(new NpcTickDemo.Limits(1, 1));
        api.spawn(1, "zone", 0, 0);
        assertThrows(IllegalStateException.class, () -> api.spawn(2, "zone", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> api.setBehavior(1, "attack"));
    }
}
