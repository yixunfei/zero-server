package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 世界创建和迁移代际、回调重入回归。 @author zn */
class WorldConcurrencyBoundaryTest {
    /** 同实体不能并行迁移；旧请求不能将更新的 owner 回退。 */
    @Test void migrationsAreExclusiveAndWorldScoped() {
        LocalWorldService service = new LocalWorldService();
        service.createWorld(new World("w", 0), List.of(new Shard("a", "w", 0), new Shard("b", "w", 0)));
        service.createWorld("other", "c");
        service.enterWorld("e", "w");
        assertThrows(RuntimeException.class, () -> service.requestMigration("cross", "e", "c"));
        service.requestMigration("m1", "e", "b");
        assertThrows(RuntimeException.class, () -> service.requestMigration("m2", "e", "a"));
        service.prepareMigration("m1");
        service.commitMigration("m1");
        service.releaseSource("m1");
        service.migrate("m3", "e", "a");
        service.commitMigration("m1");
        assertEquals("a", service.snapshot("e").ownerShardId());
    }
    /** 校验失败后同 world ID 可以重试，不留下部分 shard。 */
    @Test void failedWorldCreationDoesNotPublishPartialState() {
        LocalWorldService service = new LocalWorldService();
        assertThrows(IllegalStateException.class, () -> service.createWorld(new World("w", 0),
                List.of(new Shard("a", "w", 0), new Shard("b", "wrong", 0))));
        service.createWorld("w", "a");
        assertEquals("a", service.enterWorld("e", "w").ownerShardId());
        assertThrows(IllegalStateException.class, () -> service.createWorld(new World("x", 0),
                List.of(new Shard("b", "x", 0), new Shard("b", "x", 0))));
        service.createWorld("x", "b");
    }
    /** 写回调同步重入快速失败，不能自等永久挂起。 */
    @Test void callbackMutationFailsWithoutDeadlock() throws Exception {
        var ref = new AtomicReference<LocalWorldService>();
        var failure = new AtomicReference<RuntimeException>();
        LocalWorldService service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(), event -> {
            if (event.type() == WorldEvent.Type.MOVED) {
                failure.set(assertThrows(RuntimeException.class, () -> ref.get().moveEntity("e", "a", 2, 2)));
            }
        }, WorldMetrics.NOOP);
        ref.set(service);
        service.createWorld("w", "a");
        service.enterWorld("e", "w");
        var done = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> { try { service.moveEntity("e", "a", 1, 1); done.complete(null); }
            catch (Throwable ex) { done.completeExceptionally(ex); } });
        done.get(2, TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(failure.get());
    }
}
