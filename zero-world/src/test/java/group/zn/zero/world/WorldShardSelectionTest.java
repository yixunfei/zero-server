package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 世界入口稳定分散到全部分片，不随注册顺序改变归属。 @author zn */
class WorldShardSelectionTest {
    @Test void distributeDeterministicallyAcrossRegisteredShards() {
        var first = new LocalWorldService();
        var second = new LocalWorldService();
        var shards = List.of(new Shard("a", "world", 0), new Shard("b", "world", 0), new Shard("c", "world", 0));
        first.createWorld(new World("world", 0), shards);
        second.createWorld(new World("world", 0), shards.reversed());
        var assigned = new HashSet<String>();
        for (int index = 0; index < 100; index++) {
            String entity = "entity-" + index;
            var a = first.enterWorldAsync(entity, "world").toCompletableFuture().join();
            var b = second.enterWorldAsync(entity, "world").toCompletableFuture().join();
            assertEquals(a.ownerShardId(), b.ownerShardId());
            assigned.add(a.ownerShardId());
        }
        assertTrue(assigned.containsAll(List.of("a", "b", "c")));
    }
}
