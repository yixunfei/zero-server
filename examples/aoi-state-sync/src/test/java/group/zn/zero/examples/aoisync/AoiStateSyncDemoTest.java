package group.zn.zero.examples.aoisync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.aoi.VisibilityEvent;
import group.zn.zero.statesync.InMemoryStateSync;
import org.junit.jupiter.api.Test;

/** 核对可见实体内容、基线时序和真实全量恢复。 @author zn */
class AoiStateSyncDemoTest {
    /** 版本 2 快照的实体更新为版本 3 增量，恢复快照仍含真实实体状态。 */
    @Test
    void capturesBeforeAndAfterStateAndRecoversBaseline() {
        var result = AoiStateSyncDemo.runFlow();
        assertEquals(1, result.entered().size());
        assertEquals(VisibilityEvent.Type.ENTER, result.entered().getFirst().type());
        assertEquals(java.util.Map.of("hp", 100), result.entered().getFirst().entity().state());
        assertEquals(VisibilityEvent.Type.UPDATE, result.changed().getFirst().type());
        assertEquals(java.util.Map.of("hp", 95), result.changed().getFirst().entity().state());
        assertEquals(2, result.snapshot().stateVersion());
        assertEquals(2, result.delta().baselineVersion());
        assertEquals(3, result.delta().stateVersion());
        InMemoryStateSync freshReceiver = new InMemoryStateSync();
        assertEquals(InMemoryStateSync.Status.RESYNC_REQUIRED, freshReceiver.accept(result.delta()).status());
        assertEquals(InMemoryStateSync.Status.APPLIED, freshReceiver.accept(result.recovery()).status());
        assertEquals(InMemoryStateSync.Status.IGNORED, freshReceiver.accept(result.recovery()).status());
    }
}
