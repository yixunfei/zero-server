package group.zn.zero.data.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.DefaultPersistenceManager;
import group.zn.zero.data.persistence.ManualPersistenceScheduler;
import group.zn.zero.data.persistence.PersistenceBindingExecutor;
import group.zn.zero.data.persistence.PersistenceFlushResult;
import group.zn.zero.data.persistence.PersistenceScheduleHandle;
import group.zn.zero.data.persistence.PersistenceTarget;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 持久化管理应用示例测试。
 *
 * <p>该示例模拟游戏服内三类常见场景：玩家在线数据定时落库、场景对象小批量落库、
 * 订单等强一致数据失败重试。示例使用内存仓库承载，真实项目可替换为 MongoDB、
 * Redis 或 PostgreSQL adapter 注册出的 Repository。
 *
 * @author zn
 */
class PersistenceApplicationExampleTest {

    /**
     * 验证玩家在线数据可以通过玩家绑定键定时落库。
     */
    @Test
    void playerOnlineDataShouldFlushBySchedule() {
        ExampleGameApplication application = new ExampleGameApplication();
        application.start();

        application.loginPlayer("player-1001", "alice");
        application.addPlayerExp("player-1001", 120);
        int triggered = application.tickPersistence();
        PlayerArchive saved = application.playerArchive("player-1001").orElseThrow();

        assertEquals(1, triggered);
        assertEquals(1L, saved.version());
        assertEquals(120, saved.exp());
        assertEquals(DataThreadBinding.player("player-1001"), application.lastCapturedBinding());
        application.stop();
    }

    /**
     * 验证场景对象可以按小批量 flush，避免一次落库过多对象。
     */
    @Test
    void sceneDataShouldFlushInSmallBatches() {
        ExampleGameApplication application = new ExampleGameApplication();
        application.start();

        application.updateSceneObject("scene-9", "monster-1", 200);
        application.updateSceneObject("scene-9", "monster-2", 180);
        PersistenceFlushResult first = application.flushDirtyObjects(1);
        PersistenceFlushResult second = application.flushDirtyObjects(10);

        assertEquals(1, first.selectedCount());
        assertEquals(1, first.remainingDirtyCount());
        assertEquals(1, second.selectedCount());
        assertEquals(0, second.remainingDirtyCount());
        assertTrue(application.sceneArchive("scene-9:monster-1").isPresent());
        assertTrue(application.sceneArchive("scene-9:monster-2").isPresent());
        application.stop();
    }

    /**
     * 验证强一致数据写失败时保留脏对象，恢复后可以重试。
     */
    @Test
    void orderDataShouldRetryAfterWriteFailure() {
        ExampleGameApplication application = new ExampleGameApplication();
        application.start();

        application.failOrderRepository(true);
        application.submitOrder("order-77", "player-1001", 9900);
        PersistenceFlushResult failed = application.flushDirtyObjects(10);
        application.failOrderRepository(false);
        PersistenceFlushResult retried = application.flushDirtyObjects(10);
        OrderArchive saved = application.orderArchive("order-77").orElseThrow();

        assertEquals(1, failed.failureCount());
        assertEquals(1, failed.remainingDirtyCount());
        assertEquals(1, retried.successCount());
        assertEquals(0, retried.remainingDirtyCount());
        assertEquals("PAID", saved.status());
        application.stop();
    }

    /**
     * 示例游戏应用。
     *
     * @author zn
     */
    private static final class ExampleGameApplication {

        /**
         * 玩家仓库名称。
         */
        private static final String PLAYER_TARGET = "player";

        /**
         * 场景仓库名称。
         */
        private static final String SCENE_TARGET = "scene";

        /**
         * 订单仓库名称。
         */
        private static final String ORDER_TARGET = "order";

        /**
         * 绑定执行器。
         */
        private final RecordingBindingExecutor bindingExecutor = new RecordingBindingExecutor();

        /**
         * 持久化管理器。
         */
        private final DefaultPersistenceManager persistenceManager = new DefaultPersistenceManager(bindingExecutor);

        /**
         * 手动调度器。
         */
        private final ManualPersistenceScheduler scheduler = new ManualPersistenceScheduler();

        /**
         * 玩家数据仓库。
         */
        private final InMemoryCrudRepository<String, PlayerArchive> playerRepository = new InMemoryCrudRepository<>();

        /**
         * 场景数据仓库。
         */
        private final InMemoryCrudRepository<String, SceneArchive> sceneRepository = new InMemoryCrudRepository<>();

        /**
         * 订单数据仓库。
         */
        private final ControlledFailureRepository<OrderArchive> orderRepository = new ControlledFailureRepository<>();

        /**
         * 在线玩家状态。
         */
        private final Map<String, PlayerOnlineState> onlinePlayers = new LinkedHashMap<>();

        /**
         * 场景状态。
         */
        private final Map<String, SceneObjectState> sceneObjects = new LinkedHashMap<>();

        /**
         * 定时任务句柄。
         */
        private PersistenceScheduleHandle scheduleHandle;

        /**
         * 启动示例应用。
         */
        void start() {
            persistenceManager.start();
            persistenceManager.registerTarget(new PersistenceTarget<>(PLAYER_TARGET, playerRepository));
            persistenceManager.registerTarget(new PersistenceTarget<>(SCENE_TARGET, sceneRepository));
            persistenceManager.registerTarget(new PersistenceTarget<>(ORDER_TARGET, orderRepository));
            scheduleHandle = persistenceManager.scheduleFlush(scheduler, Duration.ofSeconds(5));
        }

        /**
         * 停止示例应用。
         */
        void stop() {
            if (scheduleHandle != null) {
                scheduleHandle.cancel();
            }
            persistenceManager.stop();
        }

        /**
         * 玩家登录。
         *
         * @param playerId 玩家 ID；不可为空。
         * @param name 玩家名；不可为空。
         */
        void loginPlayer(final String playerId, final String name) {
            onlinePlayers.put(playerId, new PlayerOnlineState(playerId, 0L, name, 1, 0));
        }

        /**
         * 增加玩家经验并登记脏数据。
         *
         * @param playerId 玩家 ID；不可为空。
         * @param expDelta 增加经验。
         */
        void addPlayerExp(final String playerId, final int expDelta) {
            PlayerOnlineState state = onlinePlayers.get(playerId);
            state.addExp(expDelta);
            persistenceManager.markDirty(
                    PLAYER_TARGET,
                    playerId,
                    DataThreadBinding.player(playerId),
                    state::snapshot);
        }

        /**
         * 推进一次持久化 tick。
         *
         * @return 触发任务数量。
         */
        int tickPersistence() {
            return scheduler.triggerAll();
        }

        /**
         * 更新场景对象并登记脏数据。
         *
         * @param sceneId 场景 ID；不可为空。
         * @param objectId 对象 ID；不可为空。
         * @param hp 当前生命值。
         */
        void updateSceneObject(final String sceneId, final String objectId, final int hp) {
            String storageId = sceneId + ":" + objectId;
            SceneObjectState state = new SceneObjectState(storageId, 0L, sceneId, objectId, hp);
            sceneObjects.put(storageId, state);
            persistenceManager.markDirty(
                    SCENE_TARGET,
                    storageId,
                    DataThreadBinding.scene(sceneId),
                    state::snapshot);
        }

        /**
         * 提交订单并登记脏数据。
         *
         * @param orderId 订单 ID；不可为空。
         * @param playerId 玩家 ID；不可为空。
         * @param amountCent 订单金额，单位为分。
         */
        void submitOrder(final String orderId, final String playerId, final long amountCent) {
            OrderArchive order = new OrderArchive(orderId, 0L, playerId, amountCent, "PAID");
            persistenceManager.markDirty(
                    ORDER_TARGET,
                    orderId,
                    DataThreadBinding.custom("orderId", orderId),
                    () -> order);
        }

        /**
         * 设置订单仓库是否写失败。
         *
         * @param fail true 表示写失败。
         */
        void failOrderRepository(final boolean fail) {
            orderRepository.failSave(fail);
        }

        /**
         * flush 指定数量脏对象。
         *
         * @param maxBatchSize 最大批量数量。
         * @return flush 结果；不可为空。
         */
        PersistenceFlushResult flushDirtyObjects(final int maxBatchSize) {
            return persistenceManager.flushNow(maxBatchSize).toCompletableFuture().join();
        }

        /**
         * 查询玩家归档。
         *
         * @param playerId 玩家 ID；不可为空。
         * @return 玩家归档；可能为空。
         */
        Optional<PlayerArchive> playerArchive(final String playerId) {
            return playerRepository.findById(playerId).toCompletableFuture().join();
        }

        /**
         * 查询场景归档。
         *
         * @param storageId 存储 ID；不可为空。
         * @return 场景归档；可能为空。
         */
        Optional<SceneArchive> sceneArchive(final String storageId) {
            return sceneRepository.findById(storageId).toCompletableFuture().join();
        }

        /**
         * 查询订单归档。
         *
         * @param orderId 订单 ID；不可为空。
         * @return 订单归档；可能为空。
         */
        Optional<OrderArchive> orderArchive(final String orderId) {
            return orderRepository.findById(orderId).toCompletableFuture().join();
        }

        /**
         * 返回最近一次捕获绑定键。
         *
         * @return 最近一次捕获绑定键；不可为空。
         */
        DataThreadBinding lastCapturedBinding() {
            return bindingExecutor.lastBinding();
        }
    }

    /**
     * 在线玩家状态。
     *
     * @author zn
     */
    private static final class PlayerOnlineState {

        /**
         * 玩家 ID。
         */
        private final String id;

        /**
         * 当前持久化版本。
         */
        private final long version;

        /**
         * 玩家名称。
         */
        private final String name;

        /**
         * 玩家等级。
         */
        private int level;

        /**
         * 当前经验。
         */
        private int exp;

        /**
         * 创建在线玩家状态。
         *
         * @param id 玩家 ID；不可为空。
         * @param version 当前持久化版本。
         * @param name 玩家名称；不可为空。
         * @param level 玩家等级。
         * @param exp 当前经验。
         */
        PlayerOnlineState(
                final String id,
                final long version,
                final String name,
                final int level,
                final int exp) {
            this.id = id;
            this.version = version;
            this.name = name;
            this.level = level;
            this.exp = exp;
        }

        /**
         * 增加经验。
         *
         * @param delta 增加经验。
         */
        void addExp(final int delta) {
            exp += delta;
            if (exp >= 100) {
                level++;
            }
        }

        /**
         * 捕获可落库快照。
         *
         * @return 玩家归档；不可为空。
         */
        PlayerArchive snapshot() {
            return new PlayerArchive(id, version, name, level, exp);
        }
    }

    /**
     * 场景对象状态。
     *
     * @author zn
     */
    private static final class SceneObjectState {

        /**
         * 存储 ID。
         */
        private final String id;

        /**
         * 当前持久化版本。
         */
        private final long version;

        /**
         * 场景 ID。
         */
        private final String sceneId;

        /**
         * 对象 ID。
         */
        private final String objectId;

        /**
         * 当前生命值。
         */
        private final int hp;

        /**
         * 创建场景对象状态。
         *
         * @param id 存储 ID；不可为空。
         * @param version 当前持久化版本。
         * @param sceneId 场景 ID；不可为空。
         * @param objectId 对象 ID；不可为空。
         * @param hp 当前生命值。
         */
        SceneObjectState(
                final String id,
                final long version,
                final String sceneId,
                final String objectId,
                final int hp) {
            this.id = id;
            this.version = version;
            this.sceneId = sceneId;
            this.objectId = objectId;
            this.hp = hp;
        }

        /**
         * 捕获可落库快照。
         *
         * @return 场景归档；不可为空。
         */
        SceneArchive snapshot() {
            return new SceneArchive(id, version, sceneId, objectId, hp);
        }
    }

    /**
     * 玩家归档。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param name 玩家名称。
     * @param level 玩家等级。
     * @param exp 当前经验。
     * @author zn
     */
    private record PlayerArchive(
            String id,
            long version,
            String name,
            int level,
            int exp) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空；线程安全。
         */
        @Override
        public PlayerArchive withVersion(final long version) {
            return new PlayerArchive(id, version, name, level, exp);
        }
    }

    /**
     * 场景归档。
     *
     * @param id 存储 ID。
     * @param version 版本号。
     * @param sceneId 场景 ID。
     * @param objectId 对象 ID。
     * @param hp 当前生命值。
     * @author zn
     */
    private record SceneArchive(
            String id,
            long version,
            String sceneId,
            String objectId,
            int hp) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空；线程安全。
         */
        @Override
        public SceneArchive withVersion(final long version) {
            return new SceneArchive(id, version, sceneId, objectId, hp);
        }
    }

    /**
     * 订单归档。
     *
     * @param id 订单 ID。
     * @param version 版本号。
     * @param playerId 玩家 ID。
     * @param amountCent 金额，单位为分。
     * @param status 订单状态。
     * @author zn
     */
    private record OrderArchive(
            String id,
            long version,
            String playerId,
            long amountCent,
            String status) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空；线程安全。
         */
        @Override
        public OrderArchive withVersion(final long version) {
            return new OrderArchive(id, version, playerId, amountCent, status);
        }
    }

    /**
     * 记录绑定键的执行器。
     *
     * @author zn
     */
    private static final class RecordingBindingExecutor implements PersistenceBindingExecutor {

        /**
         * 捕获过的绑定键。
         */
        private final List<DataThreadBinding> capturedBindings = new ArrayList<>();

        /**
         * 在当前线程捕获快照并记录绑定键。
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
            capturedBindings.add(binding);
            return CompletableFuture.completedFuture(snapshotSupplier.get());
        }

        /**
         * 返回最近一次绑定键。
         *
         * @return 最近一次绑定键；不可为空。
         */
        DataThreadBinding lastBinding() {
            return capturedBindings.get(capturedBindings.size() - 1);
        }
    }

    /**
     * 可控制保存失败的仓库。
     *
     * @param <T> 实体类型。
     * @author zn
     */
    private static final class ControlledFailureRepository<T extends VersionedEntity<String>>
            implements CrudRepository<String, T> {

        /**
         * 委托仓库。
         */
        private final InMemoryCrudRepository<String, T> delegate = new InMemoryCrudRepository<>();

        /**
         * 是否保存失败。
         */
        private final AtomicBoolean failSave = new AtomicBoolean();

        /**
         * 设置是否保存失败。
         *
         * @param failSave true 表示保存失败。
         */
        void failSave(final boolean failSave) {
            this.failSave.set(failSave);
        }

        /**
         * 根据 ID 查询对象。
         *
         * @param id 对象 ID；不可为空。
         * @return 查询结果；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Optional<T>> findById(final String id) {
            return delegate.findById(id);
        }

        /**
         * 保存对象。
         *
         * @param entity 对象；不可为空。
         * @return 保存完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> save(final T entity) {
            if (!failSave.get()) {
                return delegate.save(entity);
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new CompletionException(
                    ZeroException.of(DataErrorCode.WRITE_FAILED, "example repository write failure", null)));
            return future;
        }

        /**
         * 查询所有对象。
         *
         * @return 查询结果；不可为空；可能为空；线程安全。
         */
        @Override
        public CompletionStage<List<T>> findAll() {
            return delegate.findAll();
        }

        /**
         * 根据 ID 列表批量查询。
         *
         * @param ids 对象 ID 列表；不可为空。
         * @return 查询结果；不可为空；可能为空；线程安全。
         */
        @Override
        public CompletionStage<List<T>> findByIds(final Collection<String> ids) {
            return delegate.findByIds(ids);
        }

        /**
         * 批量保存对象。
         *
         * @param entities 对象集合；不可为空。
         * @return 保存完成信号；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> saveAll(final Collection<T> entities) {
            CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
            for (T entity : entities) {
                stage = stage.thenCompose(ignored -> save(entity));
            }
            return stage;
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
        public CompletionStage<PageResult<T>> findPage(final PageRequest request) {
            return delegate.findPage(request);
        }
    }
}
