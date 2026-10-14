package group.zn.zero.runtime.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.persistence.BoundPersistentEntity;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.DefaultPersistenceManager;
import group.zn.zero.data.persistence.PersistenceTarget;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 正式 Actor + 完成感知周期保存配方，连续保存和关闭后版本/业务值一致。 @author zn */
class ActorPersistenceRecipeTest {
    /** 更新可连续提交，周期快照、确认及停止排空均回到绑定 lane。 */
    @Test void managedPeriodicSaveReconcilesVersionsAndDrains() throws Exception {
        try (var executors = ZeroRuntimeExecutors.localPrototype("bound-save", 1);
                var scheduler = new ExecutorActorScheduler(executors.logicExecutor());
                var binding = new ActorPersistenceBindingExecutor(scheduler)) {
            var manager = new DefaultPersistenceManager(binding);
            var repository = new InMemoryCrudRepository<Long, State>();
            manager.registerTarget(new PersistenceTarget<>("state", repository));
            manager.start();
            var entity = new BoundPersistentEntity<>(new State(1L, 0, 0), "state", DataThreadBinding.player("1"), binding, manager);
            entity.update(value -> new State(value.id(), value.version(), 10)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var handle = manager.scheduleFlush(new ManagedPersistenceScheduler(executors), Duration.ofMillis(5));
            try {
                // 通过串行 flush 屏障验证周期触发与显式触发不会交叉物理写。
                assertEquals(0, manager.flushNow().toCompletableFuture().get(3, TimeUnit.SECONDS).failureCount());
                assertEquals(1, entity.snapshot().toCompletableFuture().get(3, TimeUnit.SECONDS).version());
                entity.update(value -> new State(value.id(), value.version(), 20)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            } finally { manager.stop(); }
            assertEquals(0, handle.failureCount());
            assertEquals(new State(1L, 2, 20), repository.findById(1L).toCompletableFuture().join().orElseThrow());
            assertEquals(new State(1L, 2, 20), entity.snapshot().toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
    }

    /** 不可变玩家自定义字段。 @param id 玩家。 @param version 仓库版本。 @param gold 金币。 */
    private record State(Long id, long version, int gold) implements VersionedEntity<Long> {
        /** @param next 新版本。 @return 不覆盖业务字段的副本。 */
        @Override public State withVersion(final long next) { return new State(id, next, gold); }
    }
}
