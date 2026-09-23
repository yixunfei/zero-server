package group.zn.zero.examples.aoisync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AoiStateSyncDemoTest {
    @Test void visibilityAndSequencesAreDeterministic() {
        var api = new AoiStateSyncDemo.LocalAoiApi(new AoiStateSyncDemo.Limits(10, 10, 10, 32));
        api.addObserver("o", new AoiStateSyncDemo.Point(0, 0), 3);
        api.addEntity("e", new AoiStateSyncDemo.Point(2, 0), "npc");
        assertEquals(1, api.queryVisible("o").size());
        api.moveEntity("e", new AoiStateSyncDemo.Point(10, 0));
        assertEquals(2, api.sceneSeq());
        assertEquals("disappeared", api.events("o").get(1).type());
        assertTrue(api.events("o").get(0).syncSeq() < api.events("o").get(1).syncSeq());
    }

    @Test void snapshotAndBaselineMismatchAreExplicit() {
        var api = new AoiStateSyncDemo.LocalAoiApi(new AoiStateSyncDemo.Limits(10, 10, 10, 32));
        api.addObserver("o", new AoiStateSyncDemo.Point(0, 0), 3);
        api.addEntity("e", new AoiStateSyncDemo.Point(1, 1), "npc");
        var snapshot = api.snapshot("o");
        assertEquals(1, snapshot.entities().size());
        assertTrue(api.applyDelta(new AoiStateSyncDemo.Delta(snapshot.version(), 2, "e", "ok")).applied());
        assertTrue(api.applyDelta(new AoiStateSyncDemo.Delta(99, 100, "e", "stale")).resyncRequired());
    }

    @Test void capacitiesRejectOversizedEntityAndQueueWithoutSilentDrop() {
        var api = new AoiStateSyncDemo.LocalAoiApi(new AoiStateSyncDemo.Limits(1, 1, 1, 3));
        api.addObserver("o", new AoiStateSyncDemo.Point(0, 0), 5);
        api.addEntity("e", new AoiStateSyncDemo.Point(0, 0), "npc");
        api.moveEntity("e", new AoiStateSyncDemo.Point(10, 0));
        assertThrows(IllegalStateException.class, () -> api.addEntity("second", new AoiStateSyncDemo.Point(0, 0), "x"));
        assertTrue(api.queueRejected() > 0);
    }
}
