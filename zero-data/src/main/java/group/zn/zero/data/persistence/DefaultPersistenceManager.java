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
import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
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
    /** 已物理提交但尚未完成绑定域确认的快照；与脏目录共用容量边界。 */
    private final java.util.concurrent.ConcurrentMap<DirtyKey, Confirmation> confirmations = new ConcurrentHashMap<>();

    /**
     * 默认最大批量数量。
     */
    public static final int DEFAULT_MAX_BATCH_SIZE = 1024;

    /** 默认脏对象上限，防止停机期间无限增长。 */
    public static final int DEFAULT_MAX_DIRTY_ENTRIES = 100_000;

    /** 默认最多同时保存的独立脏键数，仓库/连接池仍负责各自的资源上限。 */
    public static final int DEFAULT_MAX_CONCURRENT_WRITES = 16;

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

    /** 当前 flush；后续调用排队并各自获得自己的结果。 */
    private final AtomicReference<CompletableFuture<PersistenceFlushResult>> activeFlush = new AtomicReference<>();
    /** flush 请求队列。 */
    private final ArrayDeque<FlushRequest> queuedFlushes = new ArrayDeque<>();
    /** flush 调度锁。 */
    private final Object flushLock = new Object();
    /** 停机与新增脏入口的线性化锁，不在锁内等待外部 IO。 */
    private final Object admissionLock = new Object();
    /** 是否已经停止接受新的脏入口。 */
    private boolean stopping;
    /** 调度注册代际，禁止跨越停机边界的迟到注册重新激活定时入口。 */
    private long scheduleGeneration;
    /** 停机等待预算。 */
    private final Duration shutdownTimeout;
    /** 脏对象数量上限。 */
    private final int maxDirtyEntries;
    /** 每个脏键的失败状态；失败项不从脏表丢弃。 */
    private final ConcurrentMap<DirtyKey, PersistenceFailure> failures = new ConcurrentHashMap<>();
    /** 失败诊断对应的脏代际；迟到的旧代际失败不得阻塞新入口。 */
    private final ConcurrentMap<DirtyKey, Long> failureGenerations = new ConcurrentHashMap<>();
    /** 重试预算与退避。 */
    private final PersistenceRetryPolicy retryPolicy;
    /** 同一 flush 内的保存并发上限。 */
    private final int maxConcurrentWrites;

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
        this(serviceName, bindingExecutor, clock, Duration.ofSeconds(30));
    }

    /**
     * 创建带停机等待预算的管理器；调用方必须在关闭执行域及仓库前停止管理器。
     * @param serviceName 名称；不可为空白。
     * @param bindingExecutor 快照执行器；不可为空。
     * @param clock 统计时钟；不可为空。
     * @param shutdownTimeout 停机预算；必须为正。
     * @throws IllegalArgumentException 参数非法时抛出。
     */
    public DefaultPersistenceManager(final String serviceName, final PersistenceBindingExecutor bindingExecutor,
            final Clock clock, final Duration shutdownTimeout) {
        this(serviceName, bindingExecutor, clock, shutdownTimeout, DEFAULT_MAX_DIRTY_ENTRIES);
    }

    /** 创建带脏对象预算的持久化管理器。 */
    public DefaultPersistenceManager(final String serviceName, final PersistenceBindingExecutor bindingExecutor,
            final Clock clock, final Duration shutdownTimeout, final int maxDirtyEntries) {
        this(serviceName, bindingExecutor, clock, shutdownTimeout, maxDirtyEntries, PersistenceRetryPolicy.defaults());
    }

    /** 创建显式失败预算的管理器；线程安全，markDirty 仅内存登记，不是耐久提交。
     * @param serviceName 服务名。 @param bindingExecutor 绑定快照执行器。 @param clock 时钟。
     * @param shutdownTimeout 停机预算。 @param maxDirtyEntries 脏键上限。 @param retryPolicy 重试策略。 */
    public DefaultPersistenceManager(final String serviceName, final PersistenceBindingExecutor bindingExecutor,
            final Clock clock, final Duration shutdownTimeout, final int maxDirtyEntries,
            final PersistenceRetryPolicy retryPolicy) {
        this(serviceName, bindingExecutor, clock, shutdownTimeout, maxDirtyEntries, retryPolicy,
                DEFAULT_MAX_CONCURRENT_WRITES);
    }

    /**
     * 创建显式并发预算的线程安全管理器；不创建线程，跨脏键并行、同一键仅一项在途。
     * 快照必须在对象所属绑定域捕获，调用方应在关闭执行器和仓库前停止管理器。
     * @param serviceName 服务名称；不可空白。
     * @param bindingExecutor 快照绑定执行器；不可为空。
     * @param clock 统计时钟；不可为空。
     * @param shutdownTimeout 停机等待预算；必须为正。
     * @param maxDirtyEntries 脏键数量上限；必须为正。
     * @param retryPolicy 重试与隔离策略；不可为空。
     * @param maxConcurrentWrites 同一 flush 的在途写入上限；必须为正，应匹配驱动容量。
     * @throws NullPointerException 必要参数为空时抛出。
     * @throws IllegalArgumentException 名称空白或预算非正时抛出。
     */
    public DefaultPersistenceManager(final String serviceName, final PersistenceBindingExecutor bindingExecutor,
            final Clock clock, final Duration shutdownTimeout, final int maxDirtyEntries,
            final PersistenceRetryPolicy retryPolicy, final int maxConcurrentWrites) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        this.serviceName = requireText(serviceName, "serviceName");
        this.bindingExecutor = Objects.requireNonNull(bindingExecutor, "bindingExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxDirtyEntries <= 0) {
            throw new IllegalArgumentException("maxDirtyEntries must be positive");
        }
        this.maxDirtyEntries = maxDirtyEntries;
        if (maxConcurrentWrites <= 0) throw new IllegalArgumentException("maxConcurrentWrites must be positive");
        this.maxConcurrentWrites = maxConcurrentWrites;
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
        markDirty(targetName, id, binding, snapshotSupplier, ignored -> { });
    }

    /** {@inheritDoc} */
    @Override
    public <ID, T extends VersionedEntity<ID>> void markDirty(final String targetName, final ID id,
            final DataThreadBinding binding, final Supplier<T> snapshotSupplier,
            final java.util.function.Consumer<T> confirmed) {
        String currentTargetName = requireText(targetName, "targetName");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        Objects.requireNonNull(confirmed, "confirmed");
        requireTarget(currentTargetName);
        DirtyKey key = new DirtyKey(currentTargetName, id);
        synchronized (admissionLock) {
            if (stopping) {
                throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED, "persistence is stopping", null);
            }
            if (!dirtyEntries.containsKey(key) && dirtyEntries.size() >= maxDirtyEntries) {
                throw ZeroException.of(DataErrorCode.PERSISTENCE_DIRTY_LIMIT_EXCEEDED,
                        "persistence dirty entry limit exceeded", null);
            }
            dirtyEntries.put(key, new DirtyEntry<>(key, currentTargetName, id, binding, snapshotSupplier,
                    confirmed, dirtyMarkCount.incrementAndGet()));
        }
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
        CompletableFuture<PersistenceFlushResult> future = new CompletableFuture<>();
        boolean start;
        synchronized (flushLock) {
            start = activeFlush.get() == null;
            if (start) activeFlush.set(future);
            else if (queuedFlushes.size() < 128) queuedFlushes.addLast(new FlushRequest(future, maxBatchSize));
            else return failedFuture(DataErrorCode.PERSISTENCE_FLUSH_FAILED, "flush request queue is full", null);
        }
        if (start) startFlush(new FlushRequest(future, maxBatchSize));
        return future;
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
        long generation;
        synchronized (admissionLock) {
            if (stopping) {
                throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED, "persistence is stopping", null);
            }
            generation = scheduleGeneration;
        }
        PersistenceScheduleHandle handle = Objects.requireNonNull(scheduler.scheduleAtFixedRate(
                serviceName + "-flush",
                currentInterval,
                this::scheduledFlush), "schedule handle");
        synchronized (admissionLock) {
            if (!stopping && generation == scheduleGeneration) {
                scheduleHandles.add(handle);
                return handle;
            }
        }
        handle.cancel();
        throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                "persistence stopped during schedule registration", null);
    }

    private CompletionStage<PersistenceFlushResult> scheduledFlush() {
        return flushNow().thenApply(result -> {
            if (result.failureCount() > 0) {
                throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                        "scheduled flush failed for " + result.failureCount() + " entries; inspect persistence failures", null);
            }
            return result;
        });
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

    /** 重新启动时允许登记，残留脏入口继续保留。 */
    @Override
    protected void doStart() {
        synchronized (admissionLock) { stopping = false; }
    }

    /** 停止定时入口并在预算内保存全部脏对象；失败保留对象且生命周期进入 FAILED。 */
    @Override
    protected void doStop() {
        List<PersistenceScheduleHandle> handles;
        synchronized (admissionLock) {
            stopping = true;
            scheduleGeneration++;
            handles = List.copyOf(scheduleHandles);
            scheduleHandles.clear();
        }
        long startedAt = System.nanoTime();
        RuntimeException cancellationFailure = null;
        for (PersistenceScheduleHandle handle : handles) {
            try { handle.cancel(); }
            catch (RuntimeException failure) {
                if (cancellationFailure == null) cancellationFailure = failure;
                else cancellationFailure.addSuppressed(failure);
            }
        }
        try { drainOnStop(startedAt); }
        catch (RuntimeException failure) {
            if (cancellationFailure != null) failure.addSuppressed(cancellationFailure);
            throw failure;
        }
        if (cancellationFailure != null) throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                "persistence schedule cancellation failed", cancellationFailure);
    }

    private void drainOnStop(final long startedAt) {
        try {
            do {
                long remaining = shutdownTimeout.toNanos() - (System.nanoTime() - startedAt);
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("persistence stop timed out");
                PersistenceFlushResult result = flushNow(Integer.MAX_VALUE).toCompletableFuture()
                        .get(remaining, TimeUnit.NANOSECONDS);
                if (result.failureCount() > 0 || result.selectedCount() == 0 && !dirtyEntries.isEmpty()) {
                    throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                            "stop flush failed; retained dirty objects=" + dirtyEntries.size(), null);
                }
            } while (!dirtyEntries.isEmpty() || activeFlush.get() != null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED, "stop flush interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                    "stop flush did not complete; dirty objects retained", failure);
        }
    }

    private CompletionStage<FlushAccumulator> flushBatch(
            final List<DirtyEntry<?, ?>> batch,
            final FlushAccumulator accumulator) {
        int lanes = Math.min(maxConcurrentWrites, batch.size());
        CompletableFuture<?>[] completions = new CompletableFuture<?>[lanes];
        for (int lane = 0; lane < lanes; lane++) {
            CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
            for (int index = lane; index < batch.size(); index += lanes) {
                DirtyEntry<?, ?> entry = batch.get(index);
                stage = stage.thenCompose(ignored -> flushEntry(entry)
                    .handle((saved, throwable) -> {
                        if (throwable == null) {
                            accumulator.success();
                        } else {
                            recordFailure(entry, throwable);
                            accumulator.failure();
                        }
                        return null;
                    }));
            }
            completions[lane] = stage.toCompletableFuture();
        }
        return CompletableFuture.allOf(completions).thenApply(ignored -> accumulator);
    }

    private void startFlush(final FlushRequest request) {
        try {
            long startedAt = clock.millis();
            List<DirtyEntry<?, ?>> batch = dirtyEntries.values().stream()
                    .filter(this::readyToRetry)
                    .sorted(java.util.Comparator.comparingLong(DirtyEntry::generation))
                    .limit(request.maxBatchSize()).toList();
            flushAttemptCount.addAndGet(batch.size());
            FlushAccumulator accumulator = new FlushAccumulator(startedAt, batch.size());
            flushBatch(batch, accumulator).thenApply(this::completeResult)
                    .whenComplete((result, failure) -> finishFlush(request, result, failure));
        } catch (RuntimeException | Error failure) {
            finishFlush(request, null, failure);
        }
    }

    private void finishFlush(final FlushRequest request, final PersistenceFlushResult result,
            final Throwable failure) {
        FlushRequest next = null;
        synchronized (flushLock) {
            activeFlush.compareAndSet(request.future(), null);
            if (!queuedFlushes.isEmpty()) {
                next = queuedFlushes.removeFirst();
                activeFlush.set(next.future());
            }
        }
        if (failure == null) {
            request.future().complete(result);
        } else {
            request.future().completeExceptionally(failure);
        }
        if (next != null) {
            startFlush(next);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CompletionStage<Void> flushEntry(final DirtyEntry<?, ?> entry) {
        try {
            return flushTyped((DirtyEntry) entry);
        } catch (RuntimeException | Error failure) {
            return failedFuture(DataErrorCode.PERSISTENCE_FLUSH_FAILED, "snapshot capture failed", failure);
        }
    }

    private <ID, T extends VersionedEntity<ID>> CompletionStage<Void> flushTyped(
            final DirtyEntry<ID, T> entry) {
        PersistenceTarget<ID, T> target = target(entry.targetName());
        Confirmation pending = confirmations.get(entry.key());
        CompletionStage<Void> previous = pending == null ? CompletableFuture.completedFuture(null)
                : confirm(entry.key(), pending);
        return previous.thenCompose(ignored -> pending != null && pending.generation() == entry.generation()
                    ? CompletableFuture.completedFuture(null)
                    : bindingExecutor.capture(entry.binding(), entry.snapshotSupplier())
                            .thenCompose(entity -> saveSnapshot(entry, target, entity)))
                .thenRun(() -> {
                    synchronized (admissionLock) {
                        if (dirtyEntries.remove(entry.key(), entry)) {
                            failures.remove(entry.key());
                            failureGenerations.remove(entry.key(), entry.generation());
                        }
                    }
                });
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
        return target.repository().save(entity).thenCompose(ignored -> {
            @SuppressWarnings("unchecked")
            T saved = (T) entity.withVersion(Math.addExact(entity.version(), 1));
            var confirmation = new Confirmation(entry.generation(), entry.binding(), () -> entry.confirmed().accept(saved));
            confirmations.put(entry.key(), confirmation);
            return confirm(entry.key(), confirmation);
        });
    }

    private CompletionStage<Void> confirm(final DirtyKey key, final Confirmation confirmation) {
        return bindingExecutor.capture(confirmation.binding(), () -> {
            confirmation.action().run();
            confirmations.remove(key, confirmation);
            return (Void) null;
        });
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

    /** @return 当前失败项的不可变、可能为空诊断列表；线程安全，不移除脏数据。 */
    public List<PersistenceFailure> failures() { return List.copyOf(failures.values()); }

    /** 人工修正后重置指定键的失败预算；线程安全，不改变快照供应器。
     * @param targetName 目标名。 @param id 实体 ID。 @return 是否有失败状态被恢复。 */
    public boolean retryFailed(final String targetName, final Object id) {
        synchronized (admissionLock) {
            DirtyKey key = new DirtyKey(requireText(targetName, "targetName"), Objects.requireNonNull(id, "id"));
            failureGenerations.remove(key);
            return failures.remove(key) != null;
        }
    }

    private boolean readyToRetry(final DirtyEntry<?, ?> entry) {
        PersistenceFailure state = failures.get(entry.key());
        synchronized (admissionLock) {
            return state == null || !state.quarantined() && (stopping || clock.millis() >= state.nextAttemptAt());
        }
    }

    private void recordFailure(final DirtyEntry<?, ?> entry, final Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        var code = cause instanceof ZeroException zero ? zero.errorCode() : DataErrorCode.PERSISTENCE_FLUSH_FAILED;
        synchronized (admissionLock) {
            if (dirtyEntries.get(entry.key()) != entry) {
                return;
            }
            PersistenceFailure previous = failures.get(entry.key());
            int attempts = previous == null ? 1 : previous.attempts() + 1;
            boolean isolated = attempts >= retryPolicy.maxAttempts() || code == DataErrorCode.VERSION_CONFLICT
                    || code == DataErrorCode.INVALID_ENTITY || code == DataErrorCode.MAPPING_INVALID
                    || code == DataErrorCode.ATOMIC_WRITE_UNSUPPORTED;
            long delay = retryPolicy.delayMillis(attempts);
            long now = clock.millis();
            failures.put(entry.key(), new PersistenceFailure(entry.targetName(), entry.id(), attempts,
                    now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay, isolated, code.code()));
            failureGenerations.put(entry.key(), entry.generation());
        }
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

    /** 等待中的 flush 请求。 */
    private record FlushRequest(CompletableFuture<PersistenceFlushResult> future, int maxBatchSize) {
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
            Supplier<T> snapshotSupplier, java.util.function.Consumer<T> confirmed, long generation) {
    }

    /** 只在物理写入成功后登记，确认失败可重试；不再重写该快照。 @author zn */
    private record Confirmation(long generation, DataThreadBinding binding, Runnable action) { }

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
        private final AtomicInteger successCount = new AtomicInteger();

        /**
         * 失败数量。
         */
        private final AtomicInteger failureCount = new AtomicInteger();

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
            successCount.incrementAndGet();
        }

        /**
         * 记录失败。
         */
        void failure() {
            failureCount.incrementAndGet();
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
            return successCount.get();
        }

        /**
         * 返回失败数量。
         *
         * @return 失败数量。
         */
        int failureCount() {
            return failureCount.get();
        }
    }
}
