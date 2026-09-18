package group.zn.zero.data.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 持久化停机、失败保留与单飞回归。 @author zn */
class PersistenceReportAuditTest {
    /** 停机必须保存超过默认批量的全部入口。 */
    @Test void stopDrainsBeyondOneBatch() {
        DefaultPersistenceManager manager = new DefaultPersistenceManager();
        InMemoryCrudRepository<String, Entity> repository = new InMemoryCrudRepository<>();
        manager.registerTarget(new PersistenceTarget<>("p", repository));
        manager.start();
        for (int i = 0; i < 1030; i++) {
            String id = "p" + i;
            manager.markDirty("p", id, DataThreadBinding.unbound(), () -> new Entity(id, 0));
        }
        manager.stop();
        assertEquals(0, manager.statistics().dirtyCount());
        assertEquals(1030, manager.statistics().flushSuccessCount());
        assertEquals(LifecycleState.STOPPED, manager.state());
        assertTrue(repository.existsById("p1029").toCompletableFuture().join());
    }
    /** 捕获失败不能被静默停机吞掉。 */
    @Test void stopFailureKeepsDirtyObjectsAndReportsError() {
        DefaultPersistenceManager manager = new DefaultPersistenceManager();
        manager.registerTarget(new PersistenceTarget<>("p", new InMemoryCrudRepository<String, Entity>()));
        manager.start();
        manager.markDirty("p", "p", DataThreadBinding.unbound(), () -> { throw new IllegalStateException("capture"); });
        ZeroException failure = assertThrows(ZeroException.class, manager::stop);
        assertEquals(DataErrorCode.PERSISTENCE_FLUSH_FAILED, failure.errorCode());
        assertEquals(LifecycleState.FAILED, manager.state());
        assertEquals(1, manager.statistics().dirtyCount());
    }
    /** 异步捕获不完成时有界退出，同时并发 flush 共用结果。 */
    @Test void stalledCaptureTimesOutAndConcurrentFlushIsCoalesced() {
        BlockingCapture capture = new BlockingCapture();
        DefaultPersistenceManager manager = new DefaultPersistenceManager("p", capture, Clock.systemUTC(), Duration.ofMillis(30));
        manager.registerTarget(new PersistenceTarget<>("p", new InMemoryCrudRepository<String, Entity>()));
        manager.start();
        manager.markDirty("p", "p", DataThreadBinding.unbound(), () -> new Entity("p", 0));
        var first = manager.flushNow();
        assertEquals(first, manager.flushNow());
        assertThrows(ZeroException.class, manager::stop);
        assertEquals(1, manager.statistics().dirtyCount());
        capture.result.complete(new Entity("p", 0));
        first.toCompletableFuture().join();
        manager.stop();
        assertEquals(0, manager.statistics().dirtyCount());
    }
    /** flush 在途期间的新 markDirty 必须留给下一批，不能被旧记录移除。 */
    @Test void aNewDirtyMarkSurvivesAnOlderFlush() {
        BlockingCapture capture = new BlockingCapture();
        DefaultPersistenceManager manager = new DefaultPersistenceManager(capture);
        manager.registerTarget(new PersistenceTarget<>("p", new InMemoryCrudRepository<String, Entity>()));
        manager.markDirty("p", "p", DataThreadBinding.unbound(), () -> new Entity("p", 0));
        var flush = manager.flushNow();
        manager.markDirty("p", "p", DataThreadBinding.unbound(), () -> new Entity("p", 1));
        capture.result.complete(new Entity("p", 0));
        flush.toCompletableFuture().join();
        assertEquals(1, manager.statistics().dirtyCount());
    }
    /** 测试实体。 */
    private record Entity(String id, long version) implements VersionedEntity<String> {
        /** @return 新版本副本；线程安全。 */
        @Override public Entity withVersion(long value) { return new Entity(id, value); }
    }
    /** 由测试控制的快照捕获器。 */
    private static final class BlockingCapture implements PersistenceBindingExecutor {
        /** 唯一未完成的捕获。 */
        private final CompletableFuture<Entity> result = new CompletableFuture<>();
        /** @return 测试控制的捕获结果。 */
        @SuppressWarnings("unchecked")
        @Override public <T> CompletionStage<T> capture(DataThreadBinding binding, Supplier<T> supplier) {
            return (CompletionStage<T>) result;
        }
    }
}
