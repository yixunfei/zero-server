package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 加载协调器。
     */
    private final CacheLoadCoordinator<CacheKeyOperations.Token<K>, V> loadCoordinator;

    /** 同键写顺序和在途读取代际；后端调用不在其锁内执行。 */
    private final CacheKeyOperations<K> operations = new CacheKeyOperations<>();

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

    /**
     * 是否降级。
     */
    private volatile boolean degraded;

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
        this.policy = Objects.requireNonNull(policy, "policy");
        this.l1Cache = new InMemoryCacheService<>(policy);
        this.l2Store = l2Store;
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
        missCount.incrementAndGet();
        var token = operations.read(key);
        return readL2Entry(key, token).thenApply(entry -> entry.flatMap(CacheStoreEntry::optionalValue))
                .whenComplete((value, failure) -> operations.release(token)).toCompletableFuture().copy();
    }

    /**
     * 写入缓存。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @return 写入完成信号；不可为空；线程安全。
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
     * @return 写入完成信号；不可为空；线程安全。
     */
    public CompletionStage<Void> putVersioned(final K key, final V value, final long entityVersion) {
        if (entityVersion < 0L) {
            throw new IllegalArgumentException("entityVersion must be non-negative");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return operations.mutate(key, token -> {
            CacheStoreEntry<V> entry = normalStoreEntry(value, entityVersion);
            return backend(() -> l2Store == null ? CompletableFuture.completedFuture(true)
                    : l2Store.putIfVersion(key, entry)).thenAccept(saved -> {
                        if (!saved) throw ZeroException.of(CacheErrorCode.VERSION_CONFLICT,
                                "cache version conflict", null);
                        operations.commit(token, () -> storeL1FromL2(key, entry));
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
                                    () -> l1Cache.invalidateEntry(key, expected, entityVersion)));
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
        degraded = true;
        return writeBackFailureCount.incrementAndGet();
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
        backlogCount.set(0L);
        return 0L;
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
                degraded,
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
        return degraded;
    }

    private CompletionStage<Optional<CacheStoreEntry<V>>> readL2Entry(final K key,
            final CacheKeyOperations.Token<K> token) {
        if (l2Store == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return backend(() -> l2Store.get(key))
                .thenApply(entry -> entry.flatMap(current -> storeL1IfCurrent(key, current, token)))
                .exceptionally(throwable -> Optional.empty());
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
        try {
            write = l2Store == null ? CompletableFuture.completedFuture(true)
                    : Objects.requireNonNull(l2Store.putIfVersion(key, entry), "backend result");
        } catch (RuntimeException failure) {
            markBackendFailure();
            storeL1IfCurrent(key, entry, token);
            return CompletableFuture.completedFuture(value);
        }
        CompletableFuture<Optional<V>> result = new CompletableFuture<>();
        write.whenComplete((saved, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                markBackendFailure();
            }
            // 后端不可用时保留既有降级能力；明确版本拒绝则不得污染 L1。
            if (!result.isCancelled()
                    && !(failure instanceof java.util.concurrent.CancellationException)
                    && (failure != null || Boolean.TRUE.equals(saved))) {
                storeL1IfCurrent(key, entry, token);
            }
            result.complete(value);
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                write.toCompletableFuture().cancel(true);
            }
        });
        return result;
    }

    private <T> CompletionStage<T> backend(final java.util.function.Supplier<CompletionStage<T>> operation) {
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(operation.get(), "backend result");
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        return stage.whenComplete((ignored, failure) -> { if (failure != null) markBackendFailure(); });
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
        return cacheVersionGenerator.incrementAndGet();
    }

    private void markBackendFailure() {
        degraded = true;
        backendFailureCount.incrementAndGet();
    }

    private RuntimeException wrapCompletion(final Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return ZeroException.of(CacheErrorCode.BACKEND_UNAVAILABLE, "cache backend failed", throwable);
    }
}
