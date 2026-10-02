package group.zn.zero.data.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 故障键不可挤占全部批次，达到预算保留并隔离，只有显式恢复重置预算。 @author zn */
class PersistenceRetryBudgetTest {
    /** 时间可控地验证退避、重标脏、失败隔离和恢复，不依赖 sleep。 */
    @Test void retryBudgetSurvivesNewDirtyMarks() {
        var clock = new TestClock();
        var manager = new DefaultPersistenceManager("test", new DirectPersistenceBindingExecutor(), clock,
                Duration.ofSeconds(1), 10, new PersistenceRetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2)));
        var store = new InMemoryCrudRepository<String, Entity>();
        manager.registerTarget(new PersistenceTarget<>("p", store));
        var unavailable = new AtomicBoolean(true);
        java.util.function.Supplier<Entity> capture = () -> {
            if (unavailable.get()) throw new IllegalStateException("snapshot unavailable");
            return new Entity("p", 0);
        };
        manager.markDirty("p", "p", DataThreadBinding.unbound(), capture);
        assertEquals(1, manager.flushNow().toCompletableFuture().join().failureCount());
        manager.markDirty("p", "p", DataThreadBinding.unbound(), capture);
        assertEquals(0, manager.flushNow().toCompletableFuture().join().selectedCount());
        manager.markDirty("p", "healthy", DataThreadBinding.unbound(), () -> new Entity("healthy", 0));
        assertEquals(1, manager.flushNow().toCompletableFuture().join().successCount());
        clock.now = 1000;
        manager.flushNow().toCompletableFuture().join();
        assertTrue(manager.failures().getFirst().quarantined());
        unavailable.set(false); clock.now = 100000;
        assertEquals(0, manager.flushNow().toCompletableFuture().join().selectedCount());
        assertEquals(1, manager.statistics().dirtyCount());
        assertTrue(manager.retryFailed("p", "p"));
        assertEquals(1, manager.flushNow().toCompletableFuture().join().successCount());
        assertTrue(manager.failures().isEmpty());
    }

    /** 不可变测试实体。 */
    private record Entity(String id, long version) implements VersionedEntity<String> {
        /** @return 新版本实体。 */
        public Entity withVersion(long next) { return new Entity(id, next); }
    }
    /** 单测试线程控制的时钟。 */
    private static final class TestClock extends Clock {
        /** 测试毫秒。 */
        private long now;
        /** @return UTC。 */
        public ZoneId getZone() { return ZoneOffset.UTC; }
        /** @return 自身。 */
        public Clock withZone(ZoneId zone) { return this; }
        /** @return 当前时刻。 */
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
