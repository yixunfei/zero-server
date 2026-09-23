package group.zn.zero.statesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 快照单调性及场景隔离回归。 @author zn */
class StateReportAuditTest {
    /** 旧快照不得回退基线；其他场景不共享同一观察者的基线。 */
    @Test void staleSnapshotDoesNotRollBackAndScenesAreIsolated() {
        InMemoryStateSync sync = new InMemoryStateSync();
        sync.accept(envelope("a", 2, 0, 10, SyncEnvelope.Kind.SNAPSHOT));
        sync.accept(envelope("a", 3, 10, 11, SyncEnvelope.Kind.DELTA));
        var stale = sync.accept(envelope("a", 1, 0, 4, SyncEnvelope.Kind.SNAPSHOT));
        assertNotEquals(InMemoryStateSync.Status.APPLIED, stale.status());
        assertEquals(11, stale.baselineVersion());
        assertEquals(InMemoryStateSync.Status.APPLIED,
                sync.accept(envelope("a", 4, 11, 12, SyncEnvelope.Kind.DELTA)).status());
        assertEquals(InMemoryStateSync.Status.RESYNC_REQUIRED,
                sync.accept(envelope("b", 5, 12, 13, SyncEnvelope.Kind.DELTA)).status());
    }
    private SyncEnvelope envelope(String scene, long seq, long base, long state, SyncEnvelope.Kind kind) {
        return new SyncEnvelope(scene, "o", seq, base, state, kind, Map.of());
    }
}
