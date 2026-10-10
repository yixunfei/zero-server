package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import org.junit.jupiter.api.Test;

/** 多个本地世界服务共享受管调度器时，各自命令不能路由到别的实例。 @author zn */
class WorldSharedSchedulerTest {
    /** 同世界/实体 ID 在不同服务中保持隔离。 */
    @Test void servicesCanShareSchedulerWithoutDuplicateHandlers() {
        var scheduler = new LocalActorScheduler();
        var first = new LocalWorldService(scheduler);
        var second = new LocalWorldService(scheduler);
        first.createWorld("w", "a");
        second.createWorld("w", "b");
        assertEquals("a", first.enterWorldAsync("e", "w").toCompletableFuture().join().ownerShardId());
        assertEquals("b", second.enterWorldAsync("e", "w").toCompletableFuture().join().ownerShardId());
        assertEquals(1, first.events().size());
        assertEquals(1, second.events().size());
    }
}
