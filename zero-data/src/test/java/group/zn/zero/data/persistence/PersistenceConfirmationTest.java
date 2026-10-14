package group.zn.zero.data.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 保存确认必须更新版本、保留并发新代际，并且确认重试不能重复物理提交。 @author zn */
class PersistenceConfirmationTest {
    /** 首次写在途时继续修改，确认只推进版本，后续 flush 保存最新业务状态。 */
    @Test void confirmationPreservesNewDirtyGeneration() {
        var stored = new InMemoryCrudRepository<Long, State>();
        var release = new CompletableFuture<Void>();
        var writes = new AtomicInteger();
        CrudRepository<Long, State> repository = delayed(stored, writes, release);
        var manager = new DefaultPersistenceManager();
        manager.registerTarget(new PersistenceTarget<>("state", repository));
        var live = new AtomicReference<>(new State(1L, 0, 1));
        mark(manager, live);
        var first = manager.flushNow();
        live.set(new State(1L, 0, 2));
        mark(manager, live);
        release.complete(null);
        assertEquals(1, first.toCompletableFuture().join().remainingDirtyCount());
        assertEquals(new State(1L, 1, 2), live.get());
        assertEquals(0, manager.flushNow().toCompletableFuture().join().remainingDirtyCount());
        assertEquals(new State(1L, 2, 2), stored.findById(1L).toCompletableFuture().join().orElseThrow());
    }

    /** 确认回调失败后只重试确认，不以旧版本再次写入；新代际也等待旧确认完成。 */
    @Test void failedConfirmationIsRetriedWithoutDuplicateSave() {
        var repository = new InMemoryCrudRepository<Long, State>();
        var manager = new DefaultPersistenceManager();
        manager.registerTarget(new PersistenceTarget<>("state", repository));
        var attempts = new AtomicInteger();
        var live = new AtomicReference<>(new State(1L, 0, 1));
        manager.markDirty("state", 1L, DataThreadBinding.unbound(), live::get, saved -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("confirmation unavailable");
            live.updateAndGet(value -> value.withVersion(saved.version()));
        });
        assertEquals(1, manager.flushNow().toCompletableFuture().join().failureCount());
        manager.retryFailed("state", 1L);
        assertEquals(0, manager.flushNow().toCompletableFuture().join().failureCount());
        assertEquals(1, repository.findById(1L).toCompletableFuture().join().orElseThrow().version());
        assertEquals(1, live.get().version());
    }

    private static void mark(final DefaultPersistenceManager manager, final AtomicReference<State> live) {
        manager.markDirty("state", 1L, DataThreadBinding.unbound(), live::get,
                saved -> live.updateAndGet(value -> value.withVersion(saved.version())));
    }

    @SuppressWarnings("unchecked")
    private static CrudRepository<Long, State> delayed(final CrudRepository<Long, State> delegate,
            final AtomicInteger writes, final CompletableFuture<Void> release) {
        return (CrudRepository<Long, State>) Proxy.newProxyInstance(CrudRepository.class.getClassLoader(),
                new Class<?>[]{CrudRepository.class}, (proxy, method, args) -> {
                    Object value = method.invoke(delegate, args);
                    return method.getName().equals("save") && writes.incrementAndGet() == 1 ? release : value;
                });
    }

    /** 不可变测试快照。 @param id 主键。 @param version 版本。 @param value 业务值。 */
    private record State(Long id, long version, int value) implements VersionedEntity<Long> {
        /** @param next 新版本。 @return 保留业务字段的副本；线程安全。 */
        @Override public State withVersion(final long next) { return new State(id, next, value); }
    }
}
