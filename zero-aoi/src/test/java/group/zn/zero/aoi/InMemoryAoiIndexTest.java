package group.zn.zero.aoi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryAoiIndexTest {
    @Test void visibilityUsesChebyshevAndSequences() {
        InMemoryAoiIndex index = new InMemoryAoiIndex();
        index.add(new AoiEntity("e", new Position(3, 2), 1, "state"));
        assertTrue(index.visible(new Position(0, 0), 3).contains("e"));
        var events = index.observe("o", new Position(0, 0), 3);
        assertEquals(VisibilityEvent.Type.ENTER, events.getFirst().type());
        assertEquals(1, events.getFirst().syncSeq());
        assertEquals(0, index.observe("o", new Position(0, 0), 3).size());
    }
    @Test void leavingProducesLeaveAndExplicitResults() {
        InMemoryAoiIndex index = new InMemoryAoiIndex();
        index.add(new AoiEntity("e", new Position(0, 0), 1, null));
        index.observe("o", new Position(0, 0), 1);
        assertEquals(VisibilityEvent.Type.LEAVE, index.observe("o", new Position(9, 9), 1).getFirst().type());
        assertEquals(AoiIndex.Result.Status.NOT_FOUND, index.remove("missing").status());
    }
}
