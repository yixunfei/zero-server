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
    private final CacheLoadCoordinator<K, V> loadCoordinator;

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
        this.loadCoordinator = new CacheLoadCoordinator<>(policy.maxConcurrentLoads());
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
        return readL2(key);
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
        CacheStoreEntry<V> entry = normalStoreEntry(value, entityVersion);
        if (l2Store == null) {
            storeL1FromL2(key, entry);
            return CompletableFuture.completedFuture(null);
        }
        return l2Store.putIfVersion(key, entry).thenAccept(saved -> {
            if (!saved) {
                throw ZeroException.of(CacheErrorCode.VERSION_CONFLICT, "cache version conflict", null);
            }
            storeL1FromL2(key, entry);
        }).exceptionally(throwable -> {
            markBackendFailure();
            throw wrapCompletion(throwable);
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
        l1Cache.invalidate(key);
        if (l2Store == null) {
            return CompletableFuture.completedFuture(null);
        }
        return l2Store.invalidate(key).exceptionally(throwable -> {
            markBackendFailure();
            throw wrapCompletion(throwable);
        });
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
        l1Cache.invalidate(key);
        if (l2Store == null) {
            return CompletableFuture.completedFuture(true);
        }
        return l2Store.invalidateIfVersion(key, entityVersion).exceptionally(throwable -> {
            markBackendFailure();
            throw wrapCompletion(throwable);
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
        return readL2Entry(key).thenCompose(entry -> {
            if (entry.isPresent()) {
                hitCount.incrementAndGet();
                return CompletableFuture.completedFuture(entry.orElseThrow().optionalValue());
            }
            missCount.incrementAndGet();
            return loadCoordinator.getOrLoad(key, () -> loader.load(key)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            loadFailureCount.incrementAndGet();
                        }
                    })
                    .thenCompose(value -> {
                        loadCount.incrementAndGet();
                        return storeLoaded(key, value);
                    }));
        });
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

    private CompletionStage<Optional<V>> readL2(final K key) {
        return readL2Entry(key).thenApply(entry -> entry.flatMap(CacheStoreEntry::optionalValue));
    }

    private CompletionStage<Optional<CacheStoreEntry<V>>> readL2Entry(final K key) {
        if (l2Store == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return l2Store.get(key)
                .thenApply(entry -> entry.flatMap(current -> storeL1FromL2(key, current)))
                .exceptionally(throwable -> {
                    markBackendFailure();
                    return Optional.empty();
                });
    }

    private Optional<CacheStoreEntry<V>> storeL1FromL2(final K key, final CacheStoreEntry<V> entry) {
        if (entry.expired(Instant.now())) {
            return Optional.empty();
        }
        cacheVersionGenerator.accumulateAndGet(entry.cacheVersion(), Math::max);
        CacheEntry<V> selected = l1Cache.mergeEntry(key, new CacheEntry<>(
                entry.value(), entry.cacheVersion(), entry.expiresAt(), entry.negative()));
        return Optional.of(new CacheStoreEntry<>(selected.value(), selected.version(),
                entry.entityVersion(), selected.expiresAt(), selected.negative()));
    }

    private CompletionStage<Optional<V>> storeLoaded(final K key, final Optional<V> value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            l1Cache.putNegative(key);
            return writeL2BestEffort(key, negativeStoreEntry()).thenApply(ignored -> Optional.empty());
        }
        V current = value.orElseThrow();
        CacheStoreEntry<V> entry = normalStoreEntry(current, 0L);
        l1Cache.putVersioned(key, current, entry.cacheVersion());
        return writeL2BestEffort(key, entry).thenApply(ignored -> value);
    }

    private CompletionStage<Void> writeL2BestEffort(final K key, final CacheStoreEntry<V> entry) {
        if (l2Store == null) {
            return CompletableFuture.completedFuture(null);
        }
        return l2Store.putIfVersion(key, entry)
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        markBackendFailure();
                    }
                    return null;
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
