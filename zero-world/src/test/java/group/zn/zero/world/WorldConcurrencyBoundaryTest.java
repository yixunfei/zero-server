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
        service.enterWorldAsync("d", "w").toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> service.requestMigrationAsync("cross", "d", "c").toCompletableFuture().join());
        service.requestMigrationAsync("m1", "d", "b").toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> service.requestMigrationAsync("m2", "d", "a").toCompletableFuture().join());
        service.prepareMigrationAsync("m1").toCompletableFuture().join();
        service.commitMigrationAsync("m1").toCompletableFuture().join();
        service.releaseSourceAsync("m1").toCompletableFuture().join();
        service.migrateAsync("m3", "d", "a").toCompletableFuture().join();
        service.commitMigrationAsync("m1").toCompletableFuture().join();
        assertEquals("a", service.snapshot("d").ownerShardId());
    }
    /** 校验失败后同 world ID 可以重试，不留下部分 shard。 */
    @Test void failedWorldCreationDoesNotPublishPartialState() {
        LocalWorldService service = new LocalWorldService();
        assertThrows(IllegalStateException.class, () -> service.createWorld(new World("w", 0),
                List.of(new Shard("a", "w", 0), new Shard("b", "wrong", 0))));
        service.createWorld("w", "a");
        assertEquals("a", service.enterWorldAsync("d", "w").toCompletableFuture().join().ownerShardId());
        assertThrows(IllegalStateException.class, () -> service.createWorld(new World("x", 0),
                List.of(new Shard("b", "x", 0), new Shard("b", "x", 0))));
        service.createWorld("x", "b");
    }
    /** 回调通过异步消息追加同 lane 修改，不等待自身 lane。 */
    @Test void callbackCanEnqueueMutationWithoutDeadlock() throws Exception {
        var ref = new AtomicReference<LocalWorldService>();
        var nested = new AtomicReference<java.util.concurrent.CompletionStage<Entity>>();
        LocalWorldService service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(), event -> {
            if (event.type() == WorldEvent.Type.MOVED && ref.get().snapshot("d").x() == 1) {
                nested.set(ref.get().moveEntityAsync("d", "a", 2, 2));
            }
        }, WorldMetrics.NOOP);
        ref.set(service);
        service.createWorld("w", "a");
        service.enterWorldAsync("d", "w").toCompletableFuture().join();
        var done = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> { try { service.moveEntityAsync("d", "a", 1, 1).toCompletableFuture().join(); done.complete(null); }
            catch (Throwable ex) { done.completeExceptionally(ex); } });
        done.get(2, TimeUnit.SECONDS);
        assertEquals(2, nested.get().toCompletableFuture().get(2, TimeUnit.SECONDS).x());
    }
}
