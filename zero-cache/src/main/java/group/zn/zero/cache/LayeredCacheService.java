package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

/**
 * 本地 L1 与外部 L2 组合的缓存服务。
 *
 * <pre>
 * getOrLoad(key)
 *   -> L1
 *   -> L2
 *   -> singleflight loader
 *   -> L2 / L1 回填
 * </pre>
 *
 * 本实现不创建线程池。L2 store 如果执行远程 IO，调用方应在 Actor 线程之外包装执行边界。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public class LayeredCacheService<K, V> implements CacheService<K, V> {

    /** 最大写回重试积压，防止后端长期不可用时无界增长。 */
    private static final int MAX_WRITE_BACK_QUEUE = 1024;

    /**
     * 一级本地缓存。
     */
    private final InMemoryCacheService<K, V> l1Cache;

    /**
     * 二级缓存存储，可为空。
     */
    private final CacheStore<K, V> l2Store;

    /**
     * 缓存策略。
     */
    private final CachePolicy policy;

    /** L2 写入失败时是否允许把 loader 结果暂存到 L1；默认关闭以避免错误负缓存。 */
    private final boolean cacheLoadedValueOnBackendFailure;

    /** L2 读取失败后的单调时钟冷却窗口。 */
    private final long l2FailureCooldownNanos;

    /** 冷却窗口结束时间；0 表示当前未打开。 */
    private final AtomicLong l2CooldownUntilNanos = new AtomicLong();

    /** 冷却期间拒绝的 L2 读取次数。 */
    private final AtomicLong l2CircuitRejectedCount = new AtomicLong();

    /**
     * 加载协调器。
     */
    private final CacheLoadCoordinator<CacheKeyOperations.Token<K>, V> loadCoordinator;

    /** 同键写顺序和在途读取代际；后端调用不在其锁内执行。 */
    private final CacheKeyOperations<K> operations;

    /**
     * 缓存版本生成器。
     */
    private final AtomicLong cacheVersionGenerator = new AtomicLong();

    /**
     * 后端失败次数。
     */
    private final AtomicLong backendFailureCount = new AtomicLong();

    /**
     * 命中次数。
     */
    private final AtomicLong hitCount = new AtomicLong();

    /**
     * 未命中次数。
     */
    private final AtomicLong missCount = new AtomicLong();

    /**
     * 加载次数。
     */
    private final AtomicLong loadCount = new AtomicLong();

    /**
     * 加载失败次数。
     */
    private final AtomicLong loadFailureCount = new AtomicLong();

    /**
     * 写回失败次数。
     */
    private final AtomicLong writeBackFailureCount = new AtomicLong();

    /**
     * 积压数量。
     */
    private final AtomicLong backlogCount = new AtomicLong();

    /** 写回队列中的积压数量，不包含外部观测预留值。 */
    private final AtomicLong queuedBacklogCount = new AtomicLong();

    /** 写回失败后保留的有限重试项。 */
    private final LinkedHashMap<K, PendingWrite<K, V>> writeBackQueue = new LinkedHashMap<>();
    /** 合并同键后仍超限的拒绝次数。 */
    private final AtomicLong writeBackRejectedCount = new AtomicLong();
    /** 防止周期任务或外部手动重试重叠。 */
    private final java.util.concurrent.atomic.AtomicBoolean retrying = new java.util.concurrent.atomic.AtomicBoolean();

    /** 写回队列锁。 */
    private final Object writeBackLock = new Object();

    /**
     * 是否降级。
     */
    private final CacheRecoveryState recovery = new CacheRecoveryState();

    /**
     * 创建仅本地缓存的分层缓存服务。
     *
     * @param policy 缓存策略；不可为空。
     */
    public LayeredCacheService(final CachePolicy policy) {
        this(policy, null);
    }

    /**
     * 创建分层缓存服务。
     *
     * @param policy 缓存策略；不可为空。
     * @param l2Store 二级缓存存储；可为空。
     * @throws NullPointerException 当策略为空时抛出。
     */
    public LayeredCacheService(final CachePolicy policy, final CacheStore<K, V> l2Store) {
        this(policy, l2Store, false);
    }

    /**
     * 创建分层缓存服务并显式选择 L2 写失败时的本地降级策略。
     *
     * <p>关闭时 loader 结果仍返回给当前调用方并进入有界写回队列，但不会写入 L1；
     * 开启时允许暂存结果以换取故障期间的可用性，调用方必须接受 L1 与 L2 暂时不一致。
     *
     * @param policy 缓存策略；不可为空。
     * @param l2Store 二级缓存存储；可为空。
     * @param cacheLoadedValueOnBackendFailure 是否在 L2 写失败时回填 L1。
     */
    public LayeredCacheService(final CachePolicy policy, final CacheStore<K, V> l2Store,
            final boolean cacheLoadedValueOnBackendFailure) {
        // Preserve the historical miss/fallback behavior for compatibility. Production
        // assemblies should select a positive cooldown explicitly to prevent L2 outages
        // from turning every miss into a backend load.
        this(policy, l2Store, cacheLoadedValueOnBackendFailure, Duration.ZERO);
    }

    /**
     * 创建带 L2 故障冷却窗口的分层缓存服务。
     *
     * <p>窗口内 L1 未命中读取会快速失败，避免 Redis 故障被放大为数据库穿透；调用方可通过
     * {@code cacheLoadedValueOnBackendFailure} 显式选择写失败时的本地降级行为。
     *
     * @param policy 缓存策略；不可为空。
     * @param l2Store 二级缓存存储；可为空。
     * @param cacheLoadedValueOnBackendFailure 是否在 L2 写失败时回填 L1。
     * @param l2FailureCooldown L2 读失败后的冷却时长；不可为负。
     */
    public LayeredCacheService(final CachePolicy policy, final CacheStore<K, V> l2Store,
            final boolean cacheLoadedValueOnBackendFailure, final Duration l2FailureCooldown) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.operations = new CacheKeyOperations<>(policy.writeTimeout());
        this.l1Cache = new InMemoryCacheService<>(policy);
        this.l2Store = l2Store;
        this.cacheLoadedValueOnBackendFailure = cacheLoadedValueOnBackendFailure;
        Duration cooldown = Objects.requireNonNull(l2FailureCooldown, "l2FailureCooldown");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("l2FailureCooldown must not be negative");
        }
        this.l2FailureCooldownNanos = cooldown.toNanos();
        this.loadCoordinator = new CacheLoadCoordinator<>(policy.maxConcurrentLoads(), policy.loadTimeout());
    }

    /**
     * 读取缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存值；为空表示未命中或负缓存；线程安全。
     */
    @Override
    public CompletionStage<Optional<V>> get(final K key) {
        Objects.requireNonNull(key, "key");
        Optional<CacheEntry<V>> l1Entry = l1Cache.entryOf(key);
        if (l1Entry.isPresent()) {
            hitCount.incrementAndGet();
            return CompletableFuture.completedFuture(l1Entry.orElseThrow().optionalValue());
        }
        var token = operations.read(key);
        return readL2Entry(key, token).thenApply(entry -> {
                    if (entry.isPresent()) hitCount.incrementAndGet();
                    else missCount.incrementAndGet();
                    return entry.flatMap(CacheStoreEntry::optionalValue);
                })
                .whenComplete((value, failure) -> operations.release(token)).toCompletableFuture().copy();
    }

    /**
     * 写入缓存。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @return 写入完成信号；不可为空；线程安全；取消只停止观察，已接受的写入仍保持同键顺序。
     */
    @Override
    public CompletionStage<Void> put(final K key, final V value) {
        return putVersioned(key, value, 0L);
    }

    /**
     * 按实体版本写入缓存。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @param entityVersion 实体版本；必须大于等于 0。
     * @return 写入完成信号；不可为空；线程安全；取消只停止观察，已接受的写入仍保持同键顺序。
     */
    public CompletionStage<Void> putVersioned(final K key, final V value, final long entityVersion) {
        if (entityVersion < 0L) {
            throw new IllegalArgumentException("entityVersion must be non-negative");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return operations.mutate(key, token -> writeValue(key, value, entityVersion, token, 3));
    }

    private CompletionStage<Void> writeValue(final K key, final V value, final long entityVersion,
            final CacheKeyOperations.Token<K> token, final int attempts) {
        CompletionStage<Optional<CacheStoreEntry<V>>> observed = l2Store == null || entityVersion > 0
                ? CompletableFuture.completedFuture(Optional.empty()) : backend(() -> l2Store.get(key));
        return observed.thenCompose(previous -> {
            previous.ifPresent(entry -> {
                if (entry.entityVersion() > 0) throw ZeroException.of(CacheErrorCode.VERSION_CONFLICT,
                        "unversioned put cannot replace an entity-versioned value", null);
                cacheVersionGenerator.accumulateAndGet(entry.cacheVersion(), Math::max);
            });
            CacheStoreEntry<V> entry = normalStoreEntry(value, entityVersion);
            return backend(() -> l2Store == null ? CompletableFuture.completedFuture(true)
                    : l2Store.putIfVersion(key, entry)).thenCompose(saved -> {
                        if (!Boolean.TRUE.equals(saved)) {
                            if (entityVersion == 0 && attempts > 1) return writeValue(key, value, 0, token, attempts - 1);
                            return CompletableFuture.failedFuture(ZeroException.of(CacheErrorCode.VERSION_CONFLICT,
                                    "cache version conflict", null));
                        }
                        operations.commit(token, () -> storeL1FromL2(key, entry));
                        return CompletableFuture.completedFuture(null);
                    });
        });
    }

    /**
     * 失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 失效完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> invalidate(final K key) {
        Objects.requireNonNull(key, "key");
        return operations.mutate(key, token -> backend(() -> l2Store == null
                ? CompletableFuture.<Void>completedFuture(null) : l2Store.invalidate(key))
                .thenRun(() -> operations.commit(token, () -> l1Cache.invalidate(key))));
    }

    /**
     * 按实体版本条件失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @param entityVersion 实体版本；必须大于等于 0。
     * @return true 表示失效成功或无需失效；线程安全。
     */
    public CompletionStage<Boolean> invalidateIfVersion(final K key, final long entityVersion) {
        if (entityVersion < 0L) {
            throw new IllegalArgumentException("entityVersion must be non-negative");
        }
        Objects.requireNonNull(key, "key");
        return operations.mutate(key, token -> {
            CacheEntry<V> expected = l1Cache.entryOf(key).orElse(null);
            if (expected != null && expected.entityVersion() > entityVersion) {
                return CompletableFuture.completedFuture(false);
            }
            return backend(() -> l2Store == null ? CompletableFuture.completedFuture(true)
                    : l2Store.invalidateIfVersion(key, entityVersion)).thenApply(invalidated ->
                            Boolean.TRUE.equals(invalidated) && operations.commit(token,
                                    () -> l1Cache.invalidateEntry(key, entityVersion)));
        });
    }

    /**
     * 自动加载缓存。
     *
     * @param key 缓存键；不可为空。
     * @param loader 加载器；不可为空。
     * @return 缓存值；为空表示加载器确认不存在；线程安全。
     */
    public CompletionStage<Optional<V>> getOrLoad(final K key, final CacheLoader<K, V> loader) {
        Objects.requireNonNull(loader, "loader");
        Optional<CacheEntry<V>> l1Entry = l1Cache.entryOf(key);
        if (l1Entry.isPresent()) {
            hitCount.incrementAndGet();
            return CompletableFuture.completedFuture(l1Entry.orElseThrow().optionalValue());
        }
        var token = operations.read(key);
        return readL2Entry(key, token).thenCompose(entry -> {
            if (entry.isPresent()) {
                hitCount.incrementAndGet();
                return CompletableFuture.completedFuture(entry.orElseThrow().optionalValue());
            }
            missCount.incrementAndGet();
            return loadCoordinator.getOrLoad(token, () -> loader.load(key), value -> {
                        loadCount.incrementAndGet();
                        return operations.backfill(token, () -> storeLoaded(key, value, token),
                                () -> CompletableFuture.completedFuture(localValue(key)));
                    }).whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            loadFailureCount.incrementAndGet();
                            operations.abandon(token);
                        }
                    });
        }).whenComplete((value, failure) -> operations.release(token)).toCompletableFuture().copy();
    }

    /**
     * 标记一次写回失败。
     *
     * @return 当前写回失败次数；线程安全。
     */
    public long markWriteBackFailure() {
        synchronized (recovery) {
            long ticket = writeBackFailureCount.incrementAndGet();
            recovery.externalFailure(ticket);
            return ticket;
        }
    }

    /**
     * 调用方在对应外部写回实际成功后确认恢复；线程安全，只确认最新外部故障。
     * @param failureTicket markWriteBackFailure 返回的正数累计故障凭据。
     * @return true 表示该凭据匹配并被确认；不表示 L2、冷却或积压已全部恢复。
     */
    public boolean confirmWriteBackRecovery(final long failureTicket) {
        return recovery.confirmExternal(failureTicket);
    }

    /** 内部补偿失败归属后端域，不能制造需要外部确认的故障凭据。 */
    void markInternalWriteBackFailure() {
        synchronized (recovery) {
            writeBackFailureCount.incrementAndGet();
            recovery.failure();
        }
    }

    /**
     * 增加积压数量。
     *
     * @param amount 新增积压数量，必须大于等于 0。
     * @return 当前积压数量；线程安全。
     */
    public long addBacklog(final long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        return backlogCount.addAndGet(amount);
    }

    /**
     * 清理积压数量。
     *
     * @return 清理后的积压数量；线程安全。
     */
    public long clearBacklog() {
        List<PendingWrite<K, V>> removed;
        synchronized (writeBackLock) {
            removed = List.copyOf(writeBackQueue.values());
            writeBackQueue.clear();
            queuedBacklogCount.set(0L);
            backlogCount.set(0L);
        }
        for (var pending : removed) { operations.abandon(pending.token()); operations.release(pending.token()); }
        return 0L;
    }

    /** @return 写回队列超限拒绝次数；线程安全，拒绝不会淘汰已有条目。 */
    public long writeBackRejectedCount() { return writeBackRejectedCount.get(); }

    /** 返回物理写屏障数、超龄数和最长等待；线程安全，不释放任何在途写或泄露业务键。 */
    public CacheWriteDiagnostics writeDiagnostics() { return operations.diagnostics(); }

    /**
     * 重试此前因后端故障而保留的写回项。
     *
     * <p>调用方应在 Actor 线程之外调用。版本冲突表示 L2 已有更新，
     * 该项会被丢弃；后端失败会重新进入有界队列并保持降级状态。
     *
     * @param maxAttempts 本次最多尝试数量；必须大于等于 0。
     * @return 成功写回的条目数；不可为空；异步完成。
     */
    public CompletionStage<Integer> retryWriteBacks(final int maxAttempts) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be non-negative");
        }
        if (l2Store == null || maxAttempts == 0 || !retrying.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        List<PendingWrite<K, V>> batch;
        synchronized (writeBackLock) {
            batch = writeBackQueue.values().stream().limit(Math.min(maxAttempts, MAX_WRITE_BACK_QUEUE)).toList();
        }
        CompletionStage<Integer> chain = CompletableFuture.completedFuture(0);
        for (PendingWrite<K, V> pending : batch) {
            chain = chain.thenCompose(count -> retryWriteBack(pending).thenApply(saved -> count + (saved ? 1 : 0)));
        }
        return chain.whenComplete((ignored, failure) -> retrying.set(false)).toCompletableFuture().copy();
    }

    /**
     * 返回统计快照。
     *
     * @return 统计快照；不可为空；线程安全。
     */
    public CacheStatistics statistics() {
        CacheStatistics l1Statistics = l1Cache.statistics();
        return new CacheStatistics(
                l1Statistics.hitCount() + hitCount.get(),
                l1Statistics.missCount() + missCount.get(),
                l1Statistics.loadCount() + loadCount.get(),
                l1Statistics.loadFailureCount() + loadFailureCount.get(),
                l1Statistics.putCount(),
                l1Statistics.invalidateCount());
    }

    /**
     * 返回健康状态快照。
     *
     * @return 健康状态快照；不可为空；线程安全。
     */
    public CacheHealthSnapshot healthSnapshot() {
        return new CacheHealthSnapshot(
                degraded(),
                backendFailureCount.get(),
                writeBackFailureCount.get(),
                backlogCount.get());
    }

    /**
     * 返回是否降级。
     *
     * @return true 表示降级；线程安全。
     */
    public boolean degraded() {
        return recovery.degraded(backlogCount.get(), l2CircuitOpen());
    }

    /**
     * 返回 L2 读取熔断是否处于冷却窗口。
     *
     * @return true 表示 L2 未命中读取会快速失败；线程安全。
     */
    public boolean l2CircuitOpen() {
        return l2CooldownUntilNanos.get() > System.nanoTime();
    }

    /**
     * 返回冷却期间拒绝的读取数。
     *
     * @return 拒绝次数；线程安全。
     */
    public long l2CircuitRejectedCount() {
        return l2CircuitRejectedCount.get();
    }

    private CompletionStage<Optional<CacheStoreEntry<V>>> readL2Entry(final K key,
            final CacheKeyOperations.Token<K> token) {
        if (l2Store == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (l2CircuitOpen()) {
            l2CircuitRejectedCount.incrementAndGet();
            return CompletableFuture.failedFuture(ZeroException.of(
                    CacheErrorCode.BACKEND_UNAVAILABLE,
                    "cache backend is in cooldown",
                    null));
        }
        return backend(() -> l2Store.get(key))
                .thenApply(entry -> entry.flatMap(current -> storeL1IfCurrent(key, current, token)))
                .handle((value, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(value);
                    }
                    if (l2FailureCooldownNanos == 0L) {
                        // Compatibility mode retains the established fail-open L2 miss
                        // behavior. A positive cooldown explicitly changes this into a
                        // fail-fast boundary so callers cannot amplify an L2 outage.
                        return CompletableFuture.<Optional<CacheStoreEntry<V>>>completedFuture(Optional.empty());
                    }
                    return CompletableFuture.<Optional<CacheStoreEntry<V>>>failedFuture(wrapCompletion(failure));
                }).thenCompose(stage -> stage);
    }

    private Optional<CacheStoreEntry<V>> storeL1IfCurrent(final K key, final CacheStoreEntry<V> entry,
            final CacheKeyOperations.Token<K> token) {
        return operations.select(token, () -> storeL1FromL2(key, entry), () -> l1Cache.entryOf(key)
                .map(current -> new CacheStoreEntry<>(current.value(), current.version(), current.entityVersion(),
                        current.expiresAt(), current.negative())));
    }

    private Optional<V> localValue(final K key) {
        return l1Cache.entryOf(key).flatMap(CacheEntry::optionalValue);
    }

    private Optional<CacheStoreEntry<V>> storeL1FromL2(final K key, final CacheStoreEntry<V> entry) {
        if (entry.expired(Instant.now())) {
            return Optional.empty();
        }
        cacheVersionGenerator.accumulateAndGet(entry.cacheVersion(), Math::max);
        CacheEntry<V> selected = l1Cache.mergeEntry(key, new CacheEntry<>(
                entry.value(), entry.cacheVersion(), entry.expiresAt(), entry.negative(), entry.entityVersion()));
        return Optional.of(new CacheStoreEntry<>(selected.value(), selected.version(),
                selected.entityVersion(), selected.expiresAt(), selected.negative()));
    }

    private CompletionStage<Optional<V>> storeLoaded(final K key, final Optional<V> value,
            final CacheKeyOperations.Token<K> token) {
        Objects.requireNonNull(value, "value");
        CacheStoreEntry<V> entry = value.map(current -> normalStoreEntry(current, 0L)).orElseGet(this::negativeStoreEntry);
        if (!operations.select(token, () -> true, () -> false)) {
            return CompletableFuture.completedFuture(localValue(key));
        }
        CompletionStage<Boolean> write;
        long revision = recovery.begin();
        try {
            write = l2Store == null ? CompletableFuture.completedFuture(true)
                    : Objects.requireNonNull(l2Store.putIfVersion(key, entry), "backend result");
        } catch (RuntimeException failure) {
            markBackendFailure();
            try {
                enqueueWriteBack(key, entry, token);
                if (cacheLoadedValueOnBackendFailure) {
                    storeL1IfCurrent(key, entry, token);
                }
                return CompletableFuture.completedFuture(value);
            } catch (RuntimeException rejected) {
                return CompletableFuture.failedFuture(rejected);
            }
        }
        CompletableFuture<Optional<V>> result = new CompletableFuture<>();
        write.whenComplete((saved, failure) -> {
            try {
            if (failure == null && l2Store != null) recovery.success(revision);
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                markBackendFailure();
                enqueueWriteBack(key, entry, token);
            }
            // 默认不缓存后端写入失败的加载结果；只有显式本地降级允许回填，版本拒绝始终不回填。
            if (!result.isCancelled()
                    && !(failure instanceof java.util.concurrent.CancellationException)
                    && (failure == null && Boolean.TRUE.equals(saved)
                        || failure != null && cacheLoadedValueOnBackendFailure)) {
                storeL1IfCurrent(key, entry, token);
            }
            result.complete(value);
            } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                write.toCompletableFuture().cancel(true);
            }
        });
        return result;
    }

    private CompletionStage<Boolean> retryWriteBack(final PendingWrite<K, V> pending) {
        if (pending.entry().expired(Instant.now())) {
            removePending(pending);
            return CompletableFuture.completedFuture(false);
        }
        return operations.backfill(pending.token(),
                () -> backend(() -> l2Store.putIfVersion(pending.key(), pending.entry())),
                () -> CompletableFuture.completedFuture(false)).handle((saved, failure) -> {
                    if (failure != null) { markInternalWriteBackFailure(); return false; }
                    removePending(pending);
                    return Boolean.TRUE.equals(saved);
                });
    }

    private void removePending(final PendingWrite<K, V> pending) {
        boolean removed;
        synchronized (writeBackLock) {
            removed = writeBackQueue.remove(pending.key(), pending);
            if (removed) { queuedBacklogCount.decrementAndGet(); backlogCount.decrementAndGet(); }
        }
        if (removed) operations.release(pending.token());
    }

    private void enqueueWriteBack(final K key, final CacheStoreEntry<V> entry,
            final CacheKeyOperations.Token<K> token) {
        if (l2Store == null || !operations.retain(token)) return;
        PendingWrite<K, V> replaced;
        synchronized (writeBackLock) {
            replaced = writeBackQueue.get(key);
            if (replaced == null && writeBackQueue.size() >= MAX_WRITE_BACK_QUEUE) {
                operations.release(token);
                writeBackRejectedCount.incrementAndGet();
                markInternalWriteBackFailure();
                throw ZeroException.of(CacheErrorCode.BACKPRESSURE_REJECTED, "cache write-back queue is full", null);
            }
            if (replaced != null && replaced.entry().cacheVersion() >= entry.cacheVersion()) {
                operations.release(token); return;
            }
            writeBackQueue.put(key, new PendingWrite<>(key, entry, token));
            if (replaced == null) { queuedBacklogCount.incrementAndGet(); backlogCount.incrementAndGet(); }
        }
        if (replaced != null) operations.release(replaced.token());
    }

    /** 失败回填保留原读取代际，显式更新/失效后自动成为过期任务。 */
    private record PendingWrite<K, V>(K key, CacheStoreEntry<V> entry, CacheKeyOperations.Token<K> token) { }

    private <T> CompletionStage<T> backend(final java.util.function.Supplier<CompletionStage<T>> operation) {
        long revision = recovery.begin();
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(operation.get(), "backend result");
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        return stage.whenComplete((ignored, failure) -> {
            if (failure != null) markBackendFailure();
            else if (l2Store != null) recovery.success(revision);
        });
    }

    private CacheStoreEntry<V> normalStoreEntry(final V value, final long entityVersion) {
        return new CacheStoreEntry<>(
                Objects.requireNonNull(value, "value"),
                nextCacheVersion(),
                entityVersion,
                Instant.now().plus(policy.ttl()),
                false);
    }

    private CacheStoreEntry<V> negativeStoreEntry() {
        return new CacheStoreEntry<>(
                null,
                nextCacheVersion(),
                0L,
                Instant.now().plus(policy.negativeTtl()),
                true);
    }

    private long nextCacheVersion() {
        return cacheVersionGenerator.updateAndGet(value -> {
            if (value == Long.MAX_VALUE) throw ZeroException.of(CacheErrorCode.VERSION_CONFLICT, "cache version exhausted", null);
            return value + 1;
        });
    }

    private void markBackendFailure() {
        recovery.failure();
        backendFailureCount.incrementAndGet();
        if (l2FailureCooldownNanos > 0L) {
            long until;
            long now = System.nanoTime();
            try {
                until = Math.addExact(now, l2FailureCooldownNanos);
            } catch (ArithmeticException overflow) {
                until = Long.MAX_VALUE;
            }
            l2CooldownUntilNanos.accumulateAndGet(until, Math::max);
        }
    }

    private RuntimeException wrapCompletion(final Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return ZeroException.of(CacheErrorCode.BACKEND_UNAVAILABLE, "cache backend failed", throwable);
    }
}
