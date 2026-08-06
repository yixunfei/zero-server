package group.zn.zero.data.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 默认统一持久化管理服务测试。
 *
 * @author zn
 */
class DefaultPersistenceManagerTest {

    /**
     * 验证 flush 会按绑定执行器捕获快照并写入仓库。
     */
    @Test
    void flushShouldCaptureSnapshotWithBindingAndSaveRepository() {
        RecordingBindingExecutor bindingExecutor = new RecordingBindingExecutor();
        DefaultPersistenceManager manager = new DefaultPersistenceManager(bindingExecutor);
        InMemoryCrudRepository<String, PlayerArchive> repository = new InMemoryCrudRepository<>();
        manager.registerTarget(new PersistenceTarget<>("player", repository));

        DataThreadBinding binding = DataThreadBinding.player("player-1");
        manager.markDirty("player", "player-1", binding, () -> new PlayerArchive("player-1", 0L, "created"));
        PersistenceFlushResult result = manager.flushNow().toCompletableFuture().join();
        PlayerArchive saved = repository.findById("player-1").toCompletableFuture().join().orElseThrow();

        assertEquals(binding, bindingExecutor.lastBinding());
        assertEquals(1, result.selectedCount());
        assertEquals(1, result.successCount());
        assertEquals(0, result.failureCount());
        assertEquals(0, result.remainingDirtyCount());
        assertEquals(1L, saved.version());
        assertEquals("created", saved.name());
        assertEquals(0, manager.statistics().dirtyCount());
    }

    /**
     * 验证 flush 失败时保留脏对象，后续可以继续重试。
     */
    @Test
    void failedFlushShouldKeepDirtyEntryForRetry() {
        DefaultPersistenceManager manager = new DefaultPersistenceManager();
        ToggleFailRepository repository = new ToggleFailRepository();
        manager.registerTarget(new PersistenceTarget<>("player", repository));

        repository.fail(true);
        manager.markDirty("player", "player-2", DataThreadBinding.player("player-2"),
                () -> new PlayerArchive("player-2", 0L, "failed"));
        PersistenceFlushResult failed = manager.flushNow().toCompletableFuture().join();

        assertEquals(1, failed.failureCount());
        assertEquals(1, failed.remainingDirtyCount());
        assertEquals(1, manager.statistics().dirtyCount());

        repository.fail(false);
        manager.markDirty("player", "player-2", DataThreadBinding.player("player-2"),
                () -> new PlayerArchive("player-2", 0L, "retried"));
        PersistenceFlushResult retried = manager.flushNow().toCompletableFuture().join();
        PlayerArchive saved = repository.findById("player-2").toCompletableFuture().join().orElseThrow();

        assertEquals(1, retried.successCount());
        assertEquals(0, retried.remainingDirtyCount());
        assertEquals("retried", saved.name());
    }

    /**
     * 验证手动调度器可以触发定时 flush，且取消后不再触发。
     */
    @Test
    void manualSchedulerShouldTriggerAndCancelFlush() {
        DefaultPersistenceManager manager = new DefaultPersistenceManager();
        ManualPersistenceScheduler scheduler = new ManualPersistenceScheduler();
        InMemoryCrudRepository<String, PlayerArchive> repository = new InMemoryCrudRepository<>();
        manager.registerTarget(new PersistenceTarget<>("player", repository));
        PersistenceScheduleHandle handle = manager.scheduleFlush(scheduler, Duration.ofSeconds(1));

        manager.markDirty("player", "player-3", DataThreadBinding.player("player-3"),
                () -> new PlayerArchive("player-3", 0L, "scheduled"));
        assertEquals(1, scheduler.triggerAll());
        assertTrue(repository.existsById("player-3").toCompletableFuture().join());

        manager.markDirty("player", "player-4", DataThreadBinding.player("player-4"),
                () -> new PlayerArchive("player-4", 0L, "cancelled"));
        handle.cancel();
        assertEquals(0, scheduler.triggerAll());
        assertFalse(repository.existsById("player-4").toCompletableFuture().join());
    }

    /**
     * 验证未知目标会暴露统一错误码。
     */
    @Test
    void markDirtyShouldRejectUnknownTarget() {
        DefaultPersistenceManager manager = new DefaultPersistenceManager();

        ZeroException exception = assertThrows(ZeroException.class,
                () -> manager.markDirty("missing", "id", DataThreadBinding.unbound(),
                        () -> new PlayerArchive("id", 0L, "missing")));

        assertEquals(DataErrorCode.PERSISTENCE_TARGET_NOT_FOUND, exception.errorCode());
    }

    /**
     * 玩家归档。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param name 玩家名称。
     * @author zn
     */
    private record PlayerArchive(String id, long version, String name) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空；线程安全。
         */
        @Override
        public PlayerArchive withVersion(final long version) {
            return new PlayerArchive(id, version, name);
        }
    }

    /**
     * 记录绑定键的快照执行器。
     *
     * @author zn
     */
    private static final class RecordingBindingExecutor implements PersistenceBindingExecutor {

        /**
         * 最近一次绑定键。
         */
        private DataThreadBinding lastBinding;

        /**
         * 捕获快照并记录绑定键。
         *
         * @param binding 数据对象线程绑定键；不可为空。
         * @param snapshotSupplier 快照供应器；不可为空。
         * @param <T> 快照类型。
         * @return 快照结果；不可为空；线程安全性由测试调用方保证。
         */
        @Override
        public <T> CompletionStage<T> capture(
                final DataThreadBinding binding,
                final Supplier<T> snapshotSupplier) {
            lastBinding = binding;
            return CompletableFuture.completedFuture(snapshotSupplier.get());
        }

        /**
         * 返回最近一次绑定键。
         *
         * @return 最近一次绑定键；不可为空。
         */
        DataThreadBinding lastBinding() {
            return lastBinding;
        }
    }

    /**
     * 可切换失败状态的仓库。
     *
     * @author zn
     */
    private static final class ToggleFailRepository implements CrudRepository<String, PlayerArchive> {

        /**
         * 委托仓库。
         */
        private final InMemoryCrudRepository<String, PlayerArchive> delegate = new InMemoryCrudRepository<>();

        /**
         * 是否失败。
         */
        private final AtomicBoolean fail = new AtomicBoolean();

        /**
         * 设置是否失败。
         *
         * @param fail true 表示保存失败。
         */
        void fail(final boolean fail) {
            this.fail.set(fail);
        }

        /**
         * 根据 ID 查询对象。
         *
         * @param id 对象 ID；不可为空。
         * @return 查询结果；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Optional<PlayerArchive>> findById(final String id) {
            return delegate.findById(id);
        }

        /**
         * 保存对象。
         *
         * @param entity 对象；不可为空。
         * @return 保存完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> save(final PlayerArchive entity) {
            if (!fail.get()) {
                return delegate.save(entity);
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new CompletionException(
                    ZeroException.of(DataErrorCode.WRITE_FAILED, "forced write failure", null)));
            return future;
        }

        /**
         * 查询全部对象。
         *
         * @return 查询结果；不可为空；可能为空；线程安全。
         */
        @Override
        public CompletionStage<List<PlayerArchive>> findAll() {
            return delegate.findAll();
        }

        /**
         * 根据 ID 列表批量查询。
         *
         * @param ids 对象 ID 列表；不可为空。
         * @return 查询结果；不可为空；可能为空；线程安全。
         */
        @Override
        public CompletionStage<List<PlayerArchive>> findByIds(final Collection<String> ids) {
            return delegate.findByIds(ids);
        }

        /**
         * 批量保存对象。
         *
         * @param entities 对象集合；不可为空。
         * @return 保存完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> saveAll(final Collection<PlayerArchive> entities) {
            return delegate.saveAll(entities);
        }

        /**
         * 根据 ID 删除对象。
         *
         * @param id 对象 ID；不可为空。
         * @return 删除完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> deleteById(final String id) {
            return delegate.deleteById(id);
        }

        /**
         * 批量删除对象。
         *
         * @param ids 对象 ID 列表；不可为空。
         * @return 删除完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> deleteAll(final Collection<String> ids) {
            return delegate.deleteAll(ids);
        }

        /**
         * 判断对象是否存在。
         *
         * @param id 对象 ID；不可为空。
         * @return true 表示存在；线程安全。
         */
        @Override
        public CompletionStage<Boolean> existsById(final String id) {
            return delegate.existsById(id);
        }

        /**
         * 统计对象数量。
         *
         * @return 对象数量；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Long> count() {
            return delegate.count();
        }

        /**
         * 查询分页结果。
         *
         * @param request 分页请求；不可为空。
         * @return 分页结果；不可为空；线程安全。
         */
        @Override
        public CompletionStage<PageResult<PlayerArchive>> findPage(final PageRequest request) {
            return delegate.findPage(request);
        }
    }
}
