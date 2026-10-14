package group.zn.zero.data.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 周期调度必须观察异步完成、跳过重叠并保留失败。 @author zn */
class PersistenceAsyncScheduleTest {
    /** flush 正常返回但含失败条目时，调度观察也必须记录失败，而脏项保留。 */
    @Test void partialFlushFailureIsVisibleToSchedule() {
        var manager = new DefaultPersistenceManager();
        var repository = new group.zn.zero.data.repository.InMemoryCrudRepository<String, Entity>();
        manager.registerTarget(new PersistenceTarget<>("entities", repository));
        manager.markDirty("entities", "broken", DataThreadBinding.unbound(), () -> {
            throw new IllegalStateException("snapshot failed");
        });
        var scheduler = new ManualPersistenceScheduler();
        var handle = manager.scheduleFlush(scheduler, Duration.ofSeconds(1));
        scheduler.triggerAll();
        assertEquals(1, handle.failureCount());
        assertNotNull(handle.lastFailure());
        assertEquals(1, manager.flushNow().toCompletableFuture().join().remainingDirtyCount());
        handle.cancel();
    }

    /** 未完成时不重复排队，失败后允许下一轮且不丢失原因。 */
    @Test void completionControlsAdmissionAndFailureObservation() {
        var scheduler = new ManualPersistenceScheduler();
        var pending = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var handle = scheduler.scheduleAtFixedRate("flush", Duration.ofSeconds(1), () -> {
            calls.incrementAndGet();
            return pending;
        });
        assertEquals(1, scheduler.triggerAll());
        assertEquals(0, scheduler.triggerAll());
        assertEquals(1, calls.get());
        var cause = new IllegalStateException("storage failure");
        pending.completeExceptionally(cause);
        assertEquals(1, handle.failureCount());
        assertSame(cause, handle.lastFailure());
        assertEquals(1, scheduler.triggerAll());
        assertEquals(2, handle.failureCount());
        handle.cancel();
        assertEquals(0, scheduler.triggerAll());
    }

    /** 一个句柄取消失败也必须取消后续句柄并尝试保存已有脏数据。 */
    @Test void cancellationFailureDoesNotSkipOtherHandlesOrDrain() {
        var manager = new DefaultPersistenceManager();
        var repository = new group.zn.zero.data.repository.InMemoryCrudRepository<String, Entity>();
        manager.registerTarget(new PersistenceTarget<>("entities", repository));
        manager.markDirty("entities", "saved", DataThreadBinding.unbound(), () -> new Entity("saved", 0));
        var cancelled = new AtomicInteger();
        PersistenceScheduler scheduler = (name, interval, task) -> new PersistenceScheduleHandle() {
            @Override public void cancel() {
                if (cancelled.incrementAndGet() == 1) throw new IllegalStateException("cannot cancel first");
            }
            @Override public boolean cancelled() { return cancelled.get() > 0; }
            @Override public long failureCount() { return 0; }
            @Override public Throwable lastFailure() { return null; }
        };
        manager.scheduleFlush(scheduler, Duration.ofSeconds(1));
        manager.scheduleFlush(scheduler, Duration.ofSeconds(1));
        manager.start();
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, manager::stop);
        assertEquals(2, cancelled.get());
        assertEquals(1, repository.findById("saved").toCompletableFuture().join().orElseThrow().version());
    }

    /** 测试实体。 @param id 主键。 @param version 乐观版本。 @author zn */
    private record Entity(String id, long version) implements group.zn.zero.data.model.VersionedEntity<String> {
        /** @param next 新版本。 @return 不可变实体；线程安全，无状态修改。 */
        @Override public Entity withVersion(final long next) { return new Entity(id, next); }
    }
}
