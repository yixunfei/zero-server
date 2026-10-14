package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 实体交接必须保持令牌并在同一串行域推进。 @author zn */
class WorldHandoffSerialTest {
    /** 目标提交不提前解除独占；完成的旧释放不能干扰下一次迁移。 */
    @Test void ownershipTokenSurvivesUntilSourceRelease() {
        var service = world(new group.zn.zero.actor.scheduler.LocalActorScheduler());
        service.enterWorldAsync("d", "w").toCompletableFuture().join();
        service.requestMigrationAsync(1L, "first", "d", "b").toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> service.releaseSourceAsync("first").toCompletableFuture().join());
        assertThrows(RuntimeException.class, () -> service.commitMigrationAsync("first").toCompletableFuture().join());
        service.prepareMigrationAsync("first").toCompletableFuture().join();
        service.commitMigrationAsync("first").toCompletableFuture().join();
        assertEquals("b", service.snapshot("d").ownerShardId());
        assertThrows(RuntimeException.class, () -> service.moveEntityAsync("d", "b", 1, 1).toCompletableFuture().join());
        assertThrows(RuntimeException.class, () -> service.requestMigrationAsync(2L, "second", "d", "a").toCompletableFuture().join());
        service.releaseSourceAsync("first").toCompletableFuture().join();
        service.moveEntityAsync("d", "b", 2, 2).toCompletableFuture().join();
        service.requestMigrationAsync(2L, "second", "d", "a").toCompletableFuture().join();
        service.releaseSourceAsync("first").toCompletableFuture().join();
        assertEquals(EntityStatus.MIGRATING_OUT, service.entityStatus("d"));
        service.prepareMigrationAsync("second").toCompletableFuture().join();
        service.commitMigrationAsync("second").toCompletableFuture().join();
        service.releaseSourceAsync("first").toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> service.moveEntityAsync("d", "a", 3, 3).toCompletableFuture().join());
        service.releaseSourceAsync("second").toCompletableFuture().join();
        assertEquals("a", service.moveEntityAsync("d", "a", 3, 3).toCompletableFuture().join().ownerShardId());
    }

    /** 真实异步执行器上的完整迁移不自等；全部步骤使用同一个 entity lane。 */
    @Test void asynchronousHandoffUsesOneEntityLane() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var lanes = new CopyOnWriteArrayList<LaneKey>();
            ActorScheduler delegate = new ExecutorActorScheduler(executor);
            ActorScheduler scheduler = new ActorScheduler() {
                /** 交给真实调度器注册。 */
                @Override public ActorSubscription register(Class<?> type, ActorHandler handler) {
                    return delegate.register(type, handler);
                }
                /** 记录投递域并保留调度器语义。 */
                @Override public CompletionStage<Void> dispatch(ActorMessage message) {
                    lanes.add(message.laneKey());
                    return delegate.dispatch(message);
                }
            };
            var service = world(scheduler);
            service.enterWorldAsync("d", "w").toCompletableFuture().join();
            service.moveEntityAsync("d", "a", 1, 1).toCompletableFuture().join();
            assertEquals(MigrationState.COMPLETED,
                    service.migrateAsync(3L, "m", "d", "b").toCompletableFuture().get(3, TimeUnit.SECONDS).state());
            assertEquals("b", service.snapshot("d").ownerShardId());
            assertTrue(lanes.size() >= 6);
            assertTrue(lanes.stream().allMatch(LaneKey.entity("d")::equals));
        }
    }

    private LocalWorldService world(ActorScheduler scheduler) {
        var service = new LocalWorldService(scheduler);
        service.createWorld(new World("w", 0), List.of(new Shard("a", "w", 0), new Shard("b", "w", 0)));
        return service;
    }
}
