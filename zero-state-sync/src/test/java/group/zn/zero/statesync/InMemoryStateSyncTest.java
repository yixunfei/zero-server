package group.zn.zero.statesync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryStateSyncTest {
    @Test void deltaRequiresMatchingBaselineAndSnapshotRepairsIt() {
        InMemoryStateSync sync = new InMemoryStateSync();
        SyncEnvelope delta = new SyncEnvelope("s", "o", 1, 4, 5, SyncEnvelope.Kind.DELTA, Map.of("x", 1));
        assertEquals(InMemoryStateSync.Status.RESYNC_REQUIRED, sync.accept(delta).status());
        SyncEnvelope snapshot = new SyncEnvelope("s", "o", 2, 0, 4, SyncEnvelope.Kind.SNAPSHOT, Map.of());
        assertEquals(InMemoryStateSync.Status.APPLIED, sync.accept(snapshot).status());
        assertEquals(InMemoryStateSync.Status.APPLIED, sync.accept(new SyncEnvelope("s", "o", 3, 4, 5, SyncEnvelope.Kind.DELTA, Map.of())).status());
    }
}
