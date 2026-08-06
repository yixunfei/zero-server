package group.zn.zero.data.persistence;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.VersionedEntity;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 默认统一持久化管理服务。
 *
 * <pre>
 * markDirty(target, id, binding, snapshotSupplier)
 *   -> 记录最新脏对象入口
 * flushNow()
 *   -> 通过 bindingExecutor 在绑定执行域捕获快照
 *   -> 调用目标 Repository 保存快照
 *   -> 成功后移除对应脏入口，失败则保留等待下次 flush
 * </pre>
 *
 * 本实现不创建线程池，定时能力由 {@link PersistenceScheduler} 注入。
 *
 * @author zn
 */
public final class DefaultPersistenceManager extends AbstractLifecycle implements PersistenceManager {

    /**
     * 默认最大批量数量。
     */
    public static final int DEFAULT_MAX_BATCH_SIZE = 1024;

    /**
     * 服务名称。
     */
    private final String serviceName;

    /**
     * 绑定执行器。
     */
    private final PersistenceBindingExecutor bindingExecutor;

    /**
     * 时钟。
     */
    private final Clock clock;

    /**
     * 持久化目标表。
     */
    private final ConcurrentMap<String, PersistenceTarget<?, ?>> targets = new ConcurrentHashMap<>();

    /**
     * 脏对象表。
     */
    private final ConcurrentMap<DirtyKey, DirtyEntry<?, ?>> dirtyEntries = new ConcurrentHashMap<>();

    /**
     * 调度句柄。
     */
    private final List<PersistenceScheduleHandle> scheduleHandles = new CopyOnWriteArrayList<>();

    /**
     * 脏对象登记次数。
     */
    private final AtomicLong dirtyMarkCount = new AtomicLong();

    /**
     * flush 尝试对象数量。
     */
    private final AtomicLong flushAttemptCount = new AtomicLong();

    /**
     * flush 成功对象数量。
     */
    private final AtomicLong flushSuccessCount = new AtomicLong();

    /**
     * flush 失败对象数量。
     */
    private final AtomicLong flushFailureCount = new AtomicLong();

    /**
     * 创建默认统一持久化管理服务。
     */
    public DefaultPersistenceManager() {
        this("persistence", new DirectPersistenceBindingExecutor(), Clock.systemUTC());
    }

    /**
     * 创建统一持久化管理服务。
     *
     * @param bindingExecutor 绑定执行器；不可为空。
     * @throws NullPointerException 当绑定执行器为空时抛出。
     */
    public DefaultPersistenceManager(final PersistenceBindingExecutor bindingExecutor) {
        this("persistence", bindingExecutor, Clock.systemUTC());
    }

    /**
     * 创建统一持久化管理服务。
     *
     * @param serviceName 服务名称；不可为空。
     * @param bindingExecutor 绑定执行器；不可为空。
     * @param clock 时钟；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     * @throws IllegalArgumentException 当服务名称为空白时抛出。
     */
    public DefaultPersistenceManager(
            final String serviceName,
            final PersistenceBindingExecutor bindingExecutor,
            final Clock clock) {
        this.serviceName = requireText(serviceName, "serviceName");
        this.bindingExecutor = Objects.requireNonNull(bindingExecutor, "bindingExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 返回数据服务名称。
     *
     * @return 服务名称；不可为空；线程安全。
     */
    @Override
    public String serviceName() {
        return serviceName;
    }

    /**
     * 注册持久化目标。
     *
     * @param target 持久化目标；不可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @throws ZeroException 当目标名称重复时抛出，绑定 `DataErrorCode.MAPPING_INVALID`。
     */
    @Override
    public <ID, T extends VersionedEntity<ID>> void registerTarget(final PersistenceTarget<ID, T> target) {
        PersistenceTarget<ID, T> current = Objects.requireNonNull(target, "target");
        PersistenceTarget<?, ?> previous = targets.putIfAbsent(current.name(), current);
        if (previous != null) {
            throw ZeroException.of(
                    DataErrorCode.MAPPING_INVALID,
                    "persistence target already registered: " + current.name(),
                    null);
        }
    }

    /**
     * 登记脏对象。
     *
     * @param targetName 持久化目标名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @param binding 数据对象线程绑定键；不可为空。
     * @param snapshotSupplier 快照供应器；不可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @throws ZeroException 当目标不存在时抛出，绑定 `DataErrorCode.PERSISTENCE_TARGET_NOT_FOUND`。
     */
    @Override
    public <ID, T extends VersionedEntity<ID>> void markDirty(
            final String targetName,
            final ID id,
            final DataThreadBinding binding,
            final Supplier<T> snapshotSupplier) {
        String currentTargetName = requireText(targetName, "targetName");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        requireTarget(currentTargetName);
        DirtyKey key = new DirtyKey(currentTargetName, id);
        dirtyEntries.put(key, new DirtyEntry<>(key, currentTargetName, id, binding, snapshotSupplier));
        dirtyMarkCount.incrementAndGet();
    }

    /**
     * 立即 flush 默认批量数量的脏对象。
     *
     * @return flush 结果；不可为空；线程安全。
     */
    @Override
    public CompletionStage<PersistenceFlushResult> flushNow() {
        return flushNow(DEFAULT_MAX_BATCH_SIZE);
    }

    /**
     * 立即 flush 指定数量的脏对象。
     *
     * @param maxBatchSize 最大批量数量；必须大于 0。
     * @return flush 结果；不可为空；线程安全。
     * @throws IllegalArgumentException 当批量数量小于等于 0 时抛出。
     */
    @Override
    public CompletionStage<PersistenceFlushResult> flushNow(final int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        long startedAt = clock.millis();
        List<DirtyEntry<?, ?>> batch = dirtyEntries.values().stream()
                .limit(maxBatchSize)
                .toList();
        flushAttemptCount.addAndGet(batch.size());
        FlushAccumulator accumulator = new FlushAccumulator(startedAt, batch.size());
        return flushBatch(batch, accumulator)
                .thenApply(this::completeResult);
    }

    /**
     * 注册定时 flush。
     *
     * @param scheduler 持久化调度器；不可为空。
     * @param interval 调度间隔；不可为空且必须大于 0。
     * @return 调度句柄；不可为空；线程安全。
     */
    @Override
    public PersistenceScheduleHandle scheduleFlush(
            final PersistenceScheduler scheduler,
            final Duration interval) {
        Objects.requireNonNull(scheduler, "scheduler");
        Duration currentInterval = Objects.requireNonNull(interval, "interval");
        if (currentInterval.isZero() || currentInterval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        PersistenceScheduleHandle handle = scheduler.scheduleAtFixedRate(
                serviceName + "-flush",
                currentInterval,
                () -> flushNow().toCompletableFuture().join());
        scheduleHandles.add(handle);
        return handle;
    }

    /**
     * 返回统计快照。
     *
     * @return 统计快照；不可为空；线程安全。
     */
    @Override
    public PersistenceStatistics statistics() {
        return new PersistenceStatistics(
                dirtyEntries.size(),
                dirtyMarkCount.get(),
                flushAttemptCount.get(),
                flushSuccessCount.get(),
                flushFailureCount.get());
    }

    /**
     * 停止服务时取消所有定时任务。
     */
    @Override
    protected void doStop() {
        for (PersistenceScheduleHandle handle : scheduleHandles) {
            handle.cancel();
        }
        scheduleHandles.clear();
    }

    private CompletionStage<FlushAccumulator> flushBatch(
            final List<DirtyEntry<?, ?>> batch,
            final FlushAccumulator accumulator) {
        CompletionStage<FlushAccumulator> stage = CompletableFuture.completedFuture(accumulator);
        for (DirtyEntry<?, ?> entry : batch) {
            stage = stage.thenCompose(current -> flushEntry(entry)
                    .handle((ignored, throwable) -> {
                        if (throwable == null) {
                            current.success();
                        } else {
                            current.failure();
                        }
                        return current;
                    }));
        }
        return stage;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CompletionStage<Void> flushEntry(final DirtyEntry<?, ?> entry) {
        return flushTyped((DirtyEntry) entry);
    }

    private <ID, T extends VersionedEntity<ID>> CompletionStage<Void> flushTyped(
            final DirtyEntry<ID, T> entry) {
        PersistenceTarget<ID, T> target = target(entry.targetName());
        return bindingExecutor.capture(entry.binding(), entry.snapshotSupplier())
                .thenCompose(entity -> saveSnapshot(entry, target, entity))
                .thenRun(() -> dirtyEntries.remove(entry.key(), entry));
    }

    private <ID, T extends VersionedEntity<ID>> CompletionStage<Void> saveSnapshot(
            final DirtyEntry<ID, T> entry,
            final PersistenceTarget<ID, T> target,
            final T entity) {
        if (entity == null) {
            return failedFuture(DataErrorCode.INVALID_ENTITY, "persistence snapshot must not be null", null);
        }
        if (!entry.id().equals(entity.id())) {
            return failedFuture(DataErrorCode.INVALID_ENTITY, "persistence snapshot id mismatch", null);
        }
        return target.repository().save(entity);
    }

    private PersistenceFlushResult completeResult(final FlushAccumulator accumulator) {
        flushSuccessCount.addAndGet(accumulator.successCount());
        flushFailureCount.addAndGet(accumulator.failureCount());
        return new PersistenceFlushResult(
                accumulator.startedAtEpochMillis(),
                clock.millis(),
                accumulator.selectedCount(),
                accumulator.successCount(),
                accumulator.failureCount(),
                dirtyEntries.size());
    }

    @SuppressWarnings("unchecked")
    private <ID, T extends VersionedEntity<ID>> PersistenceTarget<ID, T> target(final String targetName) {
        return (PersistenceTarget<ID, T>) requireTarget(targetName);
    }

    private PersistenceTarget<?, ?> requireTarget(final String targetName) {
        PersistenceTarget<?, ?> target = targets.get(targetName);
        if (target == null) {
            throw ZeroException.of(
                    DataErrorCode.PERSISTENCE_TARGET_NOT_FOUND,
                    "persistence target not found: " + targetName,
                    null);
        }
        return target;
    }

    private <R> CompletionStage<R> failedFuture(
            final DataErrorCode errorCode,
            final String message,
            final Throwable cause) {
        CompletableFuture<R> future = new CompletableFuture<>();
        future.completeExceptionally(ZeroException.of(errorCode, message, cause));
        return future;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    /**
     * 脏对象键。
     *
     * @param targetName 持久化目标名称。
     * @param id 对象 ID。
     * @author zn
     */
    private record DirtyKey(String targetName, Object id) {
    }

    /**
     * 脏对象入口。
     *
     * @param key 脏对象键。
     * @param targetName 持久化目标名称。
     * @param id 对象 ID。
     * @param binding 数据对象线程绑定键。
     * @param snapshotSupplier 快照供应器。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @author zn
     */
    private record DirtyEntry<ID, T extends VersionedEntity<ID>>(
            DirtyKey key,
            String targetName,
            ID id,
            DataThreadBinding binding,
            Supplier<T> snapshotSupplier) {
    }

    /**
     * flush 计数器。
     *
     * @author zn
     */
    private static final class FlushAccumulator {

        /**
         * 开始时间戳。
         */
        private final long startedAtEpochMillis;

        /**
         * 选中对象数量。
         */
        private final int selectedCount;

        /**
         * 成功数量。
         */
        private int successCount;

        /**
         * 失败数量。
         */
        private int failureCount;

        /**
         * 创建 flush 计数器。
         *
         * @param startedAtEpochMillis 开始时间戳。
         * @param selectedCount 选中对象数量。
         */
        FlushAccumulator(final long startedAtEpochMillis, final int selectedCount) {
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.selectedCount = selectedCount;
        }

        /**
         * 记录成功。
         */
        void success() {
            successCount++;
        }

        /**
         * 记录失败。
         */
        void failure() {
            failureCount++;
        }

        /**
         * 返回开始时间戳。
         *
         * @return 开始时间戳。
         */
        long startedAtEpochMillis() {
            return startedAtEpochMillis;
        }

        /**
         * 返回选中对象数量。
         *
         * @return 选中对象数量。
         */
        int selectedCount() {
            return selectedCount;
        }

        /**
         * 返回成功数量。
         *
         * @return 成功数量。
         */
        int successCount() {
            return successCount;
        }

        /**
         * 返回失败数量。
         *
         * @return 失败数量。
         */
        int failureCount() {
            return failureCount;
        }
    }
}
