package group.zn.zero.examples.worldshard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WorldShardApplicationTest {
    @Test void migrationMarkerIsStable() {
        WorldShardApplication.Result result = WorldShardApplication.run();
        assertEquals("shard-b", result.entity().ownerShardId());
        assertEquals("COMPLETED", result.migrationState());
        assertEquals("COMPLETED", result.replayState());
        assertTrue(result.marker().startsWith("world-shard=ok|mode=local|"));
        assertTrue(result.marker().endsWith("productionReady=false"));
    }
}
